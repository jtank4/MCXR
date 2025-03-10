package net.sorenon.mcxr.play.input;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.sorenon.mcxr.core.JOMLUtil;
import net.sorenon.mcxr.core.Pose;
import net.sorenon.mcxr.play.FlatGuiManager;
import net.sorenon.mcxr.play.MCXRPlayClient;
import net.sorenon.mcxr.play.accessor.MouseExt;
import net.sorenon.mcxr.play.input.actions.Action;
import net.sorenon.mcxr.play.input.actions.SessionAwareAction;
import net.sorenon.mcxr.play.input.actionsets.GuiActionSet;
import net.sorenon.mcxr.play.input.actionsets.HandsActionSet;
import net.sorenon.mcxr.play.input.actionsets.VanillaGameplayActionSet;
import net.sorenon.mcxr.play.openxr.OpenXRInstance;
import net.sorenon.mcxr.play.openxr.OpenXRSession;
import net.sorenon.mcxr.play.openxr.XrException;
import net.sorenon.mcxr.play.openxr.XrRuntimeException;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.openxr.XR10;
import org.lwjgl.openxr.XrActionSuggestedBinding;
import org.lwjgl.openxr.XrInteractionProfileSuggestedBinding;
import org.lwjgl.openxr.XrSessionActionSetsAttachInfo;
import org.lwjgl.system.MemoryStack;
import oshi.util.tuples.Pair;

import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;

import static org.lwjgl.system.MemoryStack.stackPointers;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

import com.mojang.blaze3d.platform.InputConstants;

public final class XrInput {
    public static final HandsActionSet handsActionSet = new HandsActionSet();
    public static final VanillaGameplayActionSet vanillaGameplayActionSet = new VanillaGameplayActionSet();
    public static final GuiActionSet guiActionSet = new GuiActionSet();

    private XrInput() {
    }

    //TODO registryify this
    public static void trySetSession(OpenXRSession session) throws XrException {
        OpenXRInstance instance = session.instance;

        handsActionSet.createHandle(instance);
        vanillaGameplayActionSet.createHandle(instance);
        guiActionSet.createHandle(instance);

        HashMap<String, List<Pair<Action, String>>> defaultBindings = new HashMap<>();
        handsActionSet.getDefaultBindings(defaultBindings);
        vanillaGameplayActionSet.getDefaultBindings(defaultBindings);
        guiActionSet.getDefaultBindings(defaultBindings);

        for (var action : handsActionSet.actions()) {
            if (action instanceof SessionAwareAction sessionAwareAction) {
                sessionAwareAction.createHandleSession(session);
            }
        }

        try (var stack = stackPush()) {
            for (var entry : defaultBindings.entrySet()) {
                var bindingsSet = entry.getValue();

                XrActionSuggestedBinding.Buffer bindings = XrActionSuggestedBinding.malloc(bindingsSet.size(), stack);

                for (int i = 0; i < bindingsSet.size(); i++) {
                    var binding = bindingsSet.get(i);
                    bindings.get(i).set(
                            binding.getA().getHandle(),
                            instance.getPath(binding.getB())
                    );
                }

                XrInteractionProfileSuggestedBinding suggested_binds = XrInteractionProfileSuggestedBinding.malloc(stack).set(
                        XR10.XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING,
                        NULL,
                        instance.getPath(entry.getKey()),
                        bindings
                );

                try {
                    instance.check(XR10.xrSuggestInteractionProfileBindings(instance.handle, suggested_binds), "xrSuggestInteractionProfileBindings");
                } catch (XrRuntimeException e) {
                    StringBuilder out = new StringBuilder(e.getMessage() + "\ninteractionProfile: " + entry.getKey());
                    for (var pair : bindingsSet) {
                        out.append("\n").append(pair.getB());
                    }
                    throw new XrRuntimeException(out.toString());
                }
            }

            XrSessionActionSetsAttachInfo attach_info = XrSessionActionSetsAttachInfo.malloc(stack).set(
                    XR10.XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO,
                    NULL,
                    stackPointers(vanillaGameplayActionSet.getHandle().address(), guiActionSet.getHandle().address(), handsActionSet.getHandle().address())
            );
            // Attach the action set we just made to the session
            instance.check(XR10.xrAttachSessionActionSets(session.handle, attach_info), "xrAttachSessionActionSets");
        }
    }

    /**
     * Pre-tick + Pre-render, called once every frame
     */
    public static void pollActions() {
        if (MCXRPlayClient.INSTANCE.flatGuiManager.isScreenOpen()) {
            if (guiActionSet.exit.changedSinceLastSync) {
                if (guiActionSet.exit.currentState) {
                    if (Minecraft.getInstance().screen != null) {
                        Minecraft.getInstance().screen.keyPressed(256, 0, 0);
                    }
                }
            }
        }

        if (MCXRPlayClient.INSTANCE.flatGuiManager.isScreenOpen()) {
            return;
        }

        VanillaGameplayActionSet actionSet = vanillaGameplayActionSet;

        if (actionSet.resetPos.changedSinceLastSync) {
            if (actionSet.resetPos.currentState) {
                MCXRPlayClient.resetView();
            }
        }

        if (actionSet.turn.changedSinceLastSync) {
            float value = actionSet.turn.currentState;
            if (actionSet.turnActivated) {
                actionSet.turnActivated = Math.abs(value) > 0.15f;
            } else if (Math.abs(value) > 0.7f) {
                MCXRPlayClient.yawTurn += Math.toRadians(22) * -Math.signum(value);
                var scale = MCXRPlayClient.getCameraScale();
                Vector3f newPos = new Quaternionf().rotateLocalY(MCXRPlayClient.yawTurn).transform(MCXRPlayClient.viewSpacePoses.getRawPhysicalPose().getPos(), new Vector3f()).mul(scale);
                Vector3f wantedPos = new Vector3f(MCXRPlayClient.viewSpacePoses.getScaledPhysicalPose().getPos());

                MCXRPlayClient.xrOffset = wantedPos.sub(newPos).mul(1, 0, 1);

                actionSet.turnActivated = true;
            }
        }

        if (actionSet.hotbar.changedSinceLastSync) {
            var value = actionSet.hotbar.currentState;
            if (actionSet.hotbarActivated) {
                actionSet.hotbarActivated = Math.abs(value) > 0.15f;
            } else if (Math.abs(value) >= 0.7f) {
                if (Minecraft.getInstance().player != null)
                    Minecraft.getInstance().player.getInventory().swapPaint(-value);
                actionSet.hotbarActivated = true;
            }
        }
        if (actionSet.inventory.changedSinceLastSync) {
            if (!actionSet.inventory.currentState) {
                Minecraft client = Minecraft.getInstance();
                if (client.screen == null) {
                    if (client.player != null && client.gameMode != null) {
                        if (client.gameMode.isServerControlledInventory()) {
                            client.player.sendOpenInventory();
                        } else {
                            client.getTutorial().onOpenInventory();
                            client.setScreen(new InventoryScreen(client.player));
                        }
                    }
                }
            }
        }
        if (actionSet.sprint.changedSinceLastSync) {
            Minecraft client = Minecraft.getInstance();
            if (actionSet.sprint.currentState) {
                client.options.keySprint.setDown(true);
            } else {
                client.options.keySprint.setDown(false);
                if (client.player != null) {
                    client.player.setSprinting(false);
                }
            }
        }
        if (actionSet.sneak.changedSinceLastSync) {
            Minecraft client = Minecraft.getInstance();
            client.options.keyShift.setDown(actionSet.sneak.currentState);
        }
//        if (actionSet.attackState.changedSinceLastSync()) {
//            MinecraftClient client = MinecraftClient.getInstance();
//            InputUtil.Key key = client.options.keyAttack.getDefaultKey();
//            if (actionSet.attackState.currentState()) {
//                KeyBinding.onKeyPressed(key);
//                KeyBinding.setKeyPressed(key, true);
//            } else {
//                KeyBinding.setKeyPressed(key, false);
//            }
//        }
        if (actionSet.use.changedSinceLastSync) {
            Minecraft client = Minecraft.getInstance();
            InputConstants.Key key = client.options.keyUse.getDefaultKey();
            if (actionSet.use.currentState) {
                KeyMapping.click(key);
                KeyMapping.set(key, true);
            } else {
                KeyMapping.set(key, false);
            }
        }
    }

    /**
     * Post-tick + Pre-render, called once every frame
     */
    public static void postTick(long predictedDisplayTime) {
        FlatGuiManager FGM = MCXRPlayClient.INSTANCE.flatGuiManager;
        MouseExt mouse = (MouseExt) Minecraft.getInstance().mouseHandler;
        if (FGM.isScreenOpen()) {
            Pose pose = handsActionSet.gripPoses[MCXRPlayClient.mainHand].getUnscaledPhysicalPose();
            Vector3d pos = new Vector3d(pose.getPos());
            Vector3f dir = pose.getOrientation().rotateX((float) Math.toRadians(MCXRPlayClient.handPitchAdjust), new Quaternionf()).transform(new Vector3f(0, -1, 0));
            Vector3d result = FGM.guiRaycast(pos, new Vector3d(dir));
            if (result != null) {
                Vector3d vec = result.sub(JOMLUtil.convert(FGM.position));
                FGM.orientation.invert(new Quaterniond()).transform(vec);
                vec.y *= ((double) FGM.framebufferWidth / FGM.framebufferHeight);

                vec.x /= FGM.size;
                vec.y /= FGM.size;

                ((MouseExt) Minecraft.getInstance().mouseHandler).cursorPos(
                        FGM.framebufferWidth * (0.5 - vec.x),
                        FGM.framebufferHeight * (1 - vec.y)
                );
            }
            GuiActionSet actionSet = guiActionSet;
            if (actionSet.pickup.changedSinceLastSync || actionSet.quickMove.changedSinceLastSync) {
                if (actionSet.pickup.currentState || actionSet.quickMove.currentState) {
                    mouse.mouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS, 0);
                } else {
                    mouse.mouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE, 0);
                }
            }
            if (actionSet.split.changedSinceLastSync) {
                if (actionSet.split.currentState) {
                    mouse.mouseButton(GLFW.GLFW_MOUSE_BUTTON_RIGHT, GLFW.GLFW_PRESS, 0);
                } else {
                    mouse.mouseButton(GLFW.GLFW_MOUSE_BUTTON_RIGHT, GLFW.GLFW_RELEASE, 0);
                }
            }
            var scrollState = actionSet.scroll.currentState;
            //TODO replace with a better acc alg
            double sensitivity = 0.25;
            if (Math.abs(scrollState.y()) > 0.9 && scrollState.length() > 0.95) {
                mouse.mouseScroll(-scrollState.x() * sensitivity, 1.5 * Math.signum(scrollState.y()));
            } else if (Math.abs(scrollState.y()) > 0.1) {
                mouse.mouseScroll(-scrollState.x() * sensitivity, 0.1 * Math.signum(scrollState.y()));
            }
        }
        VanillaGameplayActionSet actionSet = vanillaGameplayActionSet;
        if (actionSet.attack.changedSinceLastSync) {
            if (actionSet.attack.currentState) {
                mouse.mouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS, 0);
            } else {
                mouse.mouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE, 0);
            }
        }
        if (actionSet.inventory.currentState) {
            long heldTime = predictedDisplayTime - actionSet.inventory.lastChangeTime;
            if (heldTime * 1E-09 > 1) {
                Minecraft.getInstance().pauseGame(false);
            }
        }
    }
}

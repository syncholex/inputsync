package net.synchole.inputsync;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import org.lwjgl.glfw.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

public final class NetworkClient {

    private static final String HOST = System.getProperty("inputsync.host", "127.0.0.1");
    private static final int PORT = Integer.parseInt(System.getProperty("inputsync.port", "25590"));

    private static volatile Socket socket;
    private static volatile PrintWriter out;
    private static volatile boolean connected = false;

    /** Assigned by server */
    private static volatile boolean isLeader = false;

    /** Prevent echo loops */
    private static final ThreadLocal<Boolean> SUPPRESS_SEND =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Latest movement state from leader */
    private static volatile MoveState lastMove = null;

    /** Rate limiting */
    private static long lastSendNanos = 0L;

    /** GLFW callbacks */
    private static volatile boolean callbacksInstalled = false;
    private static GLFWCursorPosCallbackI prevCursorCb;
    private static GLFWMouseButtonCallbackI prevMouseCb;
    private static GLFWScrollCallbackI prevScrollCb;
    private static GLFWKeyCallbackI prevKeyCb;
    private static GLFWCharCallbackI prevCharCb;

    /** UI cursor tracking (scaled GUI coords) */
    private static volatile double uiX = 0.0, uiY = 0.0;
    private static volatile int uiMods = 0;

    /** Hotbar selected slot reflection cache */
    private static volatile Field invSelectedSlotField = null;

    /** Follower edge tracking */
    private static volatile boolean lastAttackHeld = false;
    private static volatile boolean lastUseHeld = false;

    /** Chat text field cache */
    private static volatile Field chatTextFieldField = null;
    private static volatile boolean chatFieldSearched = false;
    private static volatile String chatFieldOwner = null;

    /** Cache likely "text value" String field in TextFieldWidget */
    private static volatile Field chatTextValueField = null;
    private static volatile String chatTextValueOwner = null;

    /** Only send CHAT_SET when changed */
    private static volatile String lastChatSentLeader = null;

    /** One-time UI signature dump */
    private static volatile boolean dumpedUiOnce = false;

    /** Dump packed ctor/methods per screen class */
    private static volatile String dumpedPackedForScreen = null;

    /** Cache packed handler method per screen class */
    private static volatile Method cachedPackedHandler = null;
    private static volatile String cachedPackedHandlerOwner = null;

    /** Cached HandledScreen gui origin fields */
    private static volatile Field handledXField = null;
    private static volatile Field handledYField = null;

    /** Cached HandledScreen hovered slot field */
    private static volatile Field handledHoveredSlotField = null;

    /** Follower block breaking state */
    private static volatile boolean wasBreaking = false;

    private static volatile boolean started = false;

    private NetworkClient() {}

    /* ========================================================= */
    /* ===================== LIFECYCLE ========================= */
    /* ========================================================= */

    public static void start() {
        if (started) return;
        started = true;

        System.out.println("[InputSync] START " + HOST + ":" + PORT);

        installGlfwCallbacksWhenReady();

        Thread t = new Thread(NetworkClient::connectLoop, "InputSync-ConnectLoop");
        t.setDaemon(true);
        t.start();
    }

    /** Called from ClientTickEvents.END_CLIENT_TICK.register(NetworkClient::onTick); */
    public static void onTick(MinecraftClient mc) {
        if (mc == null) return;
        if (!connected || !CommandHandler.isEnabled()) return;

        // Followers: apply leader state every tick
        if (!isLeader) {
            if (lastMove != null) {
                suppressSending(() -> {
                    try {
                        lastMove.apply(mc);
                    } catch (Throwable t) {
                        System.out.println("[InputSync] APPLY FAILED: " + t);
                    }
                });
            }
            return;
        }

        // Leader: send state ~30Hz
        if (mc.player == null) return;

        long now = System.nanoTime();
        if (now - lastSendNanos < 33_000_000L) return;
        lastSendNanos = now;

        if (Boolean.TRUE.equals(SUPPRESS_SEND.get())) return;

        MoveState st = MoveState.capture(mc);
        if (st != null) sendToServer(st.encode());

        // Leader: if chat is open, send full buffer state ONLY when it changes
        if (mc.currentScreen instanceof ChatScreen) {
            String cur = readChatBuffer(mc);
            if (cur == null) cur = "";
            if (lastChatSentLeader == null || !lastChatSentLeader.equals(cur)) {
                lastChatSentLeader = cur;
                sendToServer("CHAT_SET_" + b64(cur));
            }
        } else {
            lastChatSentLeader = null;
        }
    }

    /* ========================================================= */
    /* ===================== GLFW CAPTURE ====================== */
    /* ========================================================= */

    private static void installGlfwCallbacksWhenReady() {
        Thread t = new Thread(() -> {
            while (!callbacksInstalled) {
                try {
                    MinecraftClient mc = MinecraftClient.getInstance();
                    if (mc != null) {
                        mc.execute(() -> {
                            if (callbacksInstalled) return;

                            long handle = mc.getWindow().getHandle();
                            if (handle == 0L) return;

                            callbacksInstalled = true;

                            prevCursorCb = GLFW.glfwSetCursorPosCallback(handle, (w, x, y) -> {
                                if (prevCursorCb != null) prevCursorCb.invoke(w, x, y);

                                double scale = mc.getWindow().getScaleFactor();
                                uiX = x / scale;
                                uiY = y / scale;

                                if (!shouldSendNow()) return;
                                sendToServer("UI_MOVE_" + uiX + "_" + uiY);
                            });

                            prevMouseCb = GLFW.glfwSetMouseButtonCallback(handle, (w, b, a, m) -> {
                                if (prevMouseCb != null) prevMouseCb.invoke(w, b, a, m);
                                uiMods = m;

                                if (!shouldSendNow()) return;

                                // Inventory clicks: send slot click using hovered slot (most reliable)
                                if (mc.currentScreen instanceof HandledScreen<?> hs && mc.player != null && mc.interactionManager != null) {
                                    if (a == GLFW.GLFW_PRESS) {
                                        int syncId = hs.getScreenHandler().syncId;

                                        int slotId = getHoveredSlotId(hs);
                                        if (slotId == -999) slotId = slotIdFromMouse(hs, uiX, uiY);

                                        int button = (b == GLFW.GLFW_MOUSE_BUTTON_RIGHT) ? 1 : 0;
                                        boolean shift = (m & GLFW.GLFW_MOD_SHIFT) != 0;
                                        int action = shift ? SlotActionType.QUICK_MOVE.ordinal() : SlotActionType.PICKUP.ordinal();

                                        sendToServer("INV_" + syncId + "_" + slotId + "_" + button + "_" + action);
                                        return;
                                    }
                                }

                                // Debug
                                if (a == GLFW.GLFW_PRESS) {
                                    sendToServer("UI_CLICK_" + b + "_" + uiX + "_" + uiY + "_" + m);
                                } else if (a == GLFW.GLFW_RELEASE) {
                                    sendToServer("UI_RELEASE_" + b + "_" + uiX + "_" + uiY + "_" + m);
                                }
                            });

                            prevScrollCb = GLFW.glfwSetScrollCallback(handle, (w, dx, dy) -> {
                                if (prevScrollCb != null) prevScrollCb.invoke(w, dx, dy);
                                if (!shouldSendNow()) return;
                                sendToServer("UI_SCROLL_" + uiX + "_" + uiY + "_" + dx + "_" + dy);
                            });

                            // Decide intent based on screen BEFORE Minecraft handles the key
                            prevKeyCb = GLFW.glfwSetKeyCallback(handle, (w, k, sc, a, m) -> {
                                Screen before = mc.currentScreen; // capture BEFORE vanilla flips screen state
                                uiMods = m;

                                if (prevKeyCb != null) prevKeyCb.invoke(w, k, sc, a, m);

                                if (!shouldSendNow()) return;

                                if (a != GLFW.GLFW_PRESS) return;

                                // Chat: only handle Enter to send + Escape to close.
                                // IMPORTANT: return so keys like "E" are NOT hijacked by inventory/menu logic.
                                if (before instanceof ChatScreen) {
                                    if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) {
                                        sendToServer("CHAT_SEND");
                                    } else if (k == GLFW.GLFW_KEY_ESCAPE) {
                                        sendToServer("CLOSE_SCREEN");
                                    }
                                    return;
                                }

                                if (k == GLFW.GLFW_KEY_ESCAPE) {
                                    if (before == null) sendToServer("OPEN_MENU");
                                    else sendToServer("CLOSE_SCREEN");
                                    return;
                                }

                                if (k == GLFW.GLFW_KEY_E) {
                                    if (before == null) sendToServer("OPEN_INV");
                                    else sendToServer("CLOSE_SCREEN");
                                    return;
                                }

                                if (before == null) {
                                    if (k == GLFW.GLFW_KEY_T) {
                                        sendToServer("OPEN_CHAT");
                                        return;
                                    }
                                    if (k == GLFW.GLFW_KEY_SLASH) {
                                        sendToServer("OPEN_CMD");
                                        return;
                                    }
                                }

                                if (k >= GLFW.GLFW_KEY_1 && k <= GLFW.GLFW_KEY_9) {
                                    int slot = k - GLFW.GLFW_KEY_1;
                                    sendToServer("HBAR_" + slot);
                                }
                            });

                            prevCharCb = GLFW.glfwSetCharCallback(handle, (w, cp) -> {
                                if (prevCharCb != null) prevCharCb.invoke(w, cp);
                                if (!shouldSendNow()) return;

                                // Debug only; chat uses CHAT_SET sync
                                sendToServer("UI_CHAR_" + cp + "_" + uiMods);
                            });

                            System.out.println("[InputSync] GLFW callbacks installed");
                        });
                    }
                    Thread.sleep(200);
                } catch (Throwable ignored) {}
            }
        }, "InputSync-GlfwInstaller");

        t.setDaemon(true);
        t.start();
    }

    /* ========================================================= */
    /* ===================== NETWORK =========================== */
    /* ========================================================= */

    private static void connectLoop() {
        while (true) {
            try {
                if (!connected) tryConnect();
                Thread.sleep(1000);
            } catch (Throwable ignored) {}
        }
    }

    private static void tryConnect() {
        cleanupSocket();
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(HOST, PORT), 1500);
            s.setTcpNoDelay(true);

            out = new PrintWriter(s.getOutputStream(), true);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));

            socket = s;
            connected = true;
            isLeader = false;

            new Thread(() -> readerLoop(in), "InputSync-Reader").start();
            System.out.println("[InputSync] CONNECTED");
        } catch (Exception ignored) {
            connected = false;
        }
    }

    private static void readerLoop(BufferedReader in) {
        try {
            String line;
            while ((line = in.readLine()) != null) handleIncoming(line.trim());
        } catch (Exception ignored) {
        } finally {
            connected = false;
            isLeader = false;
            cleanupSocket();
        }
    }

    private static void handleIncoming(String msg) {
        if (msg.isEmpty()) return;

        if ("ROLE_LEADER".equals(msg)) { isLeader = true;  System.out.println("[InputSync] ROLE=LEADER"); return; }
        if ("ROLE_FOLLOWER".equals(msg)) { isLeader = false; System.out.println("[InputSync] ROLE=FOLLOWER"); return; }

        if ("PAUSE".equals(msg)) { CommandHandler.setEnabled(false); return; }
        if ("RESUME".equals(msg)) { CommandHandler.setEnabled(true); return; }
        if ("TOGGLE_SYNC".equals(msg)) { CommandHandler.toggle(); return; }

        if (!CommandHandler.isEnabled()) return;

        if (msg.startsWith("MOVE_")) {
            if (!isLeader) lastMove = MoveState.parse(msg);
            return;
        }

        if ("CLOSE_SCREEN".equals(msg)) {
            if (!isLeader) {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc != null) mc.execute(() -> suppressSending(() -> mc.setScreen(null)));
            }
            return;
        }

        if (msg.startsWith("OPEN_")) {
            if (!isLeader) {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc != null) mc.execute(() -> suppressSending(() -> applyOpen(mc, msg)));
            }
            return;
        }

        if (msg.startsWith("HBAR_")) {
            if (!isLeader) {
                int slot = safeParseInt(msg.substring("HBAR_".length()), 0);
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc != null) mc.execute(() -> suppressSending(() -> setSelectedHotbarSlot(mc, slot)));
            }
            return;
        }

        if (msg.startsWith("INV_")) {
            if (!isLeader) {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc != null) mc.execute(() -> suppressSending(() -> applyInvClick(mc, msg)));
            }
            return;
        }

        if (msg.startsWith("CHAT_SET_")) {
            if (!isLeader) {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc != null) mc.execute(() -> suppressSending(() -> applyChatSet(mc, msg)));
            }
            return;
        }

        if ("CHAT_SEND".equals(msg)) {
            if (!isLeader) {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc != null) mc.execute(() -> suppressSending(() -> applyChatSend(mc)));
            }
            return;
        }

        // UI debug only
        if (msg.startsWith("UI_")) {
            if (!isLeader) {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc != null) mc.execute(() -> suppressSending(() -> applyUiDebug(mc, msg)));
            }
        }
    }

    /* ========================================================= */
    /* ===================== APPLY: OPEN SCREENS =============== */
    /* ========================================================= */

    private static void applyOpen(MinecraftClient mc, String msg) {
        try {
            switch (msg) {
                case "OPEN_INV" -> {
                    if (mc.player != null && mc.currentScreen == null) {
                        mc.setScreen(new InventoryScreen(mc.player));
                    }
                }
                case "OPEN_CHAT" -> {
                    Screen s = newChatScreen("", false);
                    if (s != null) mc.setScreen(s);
                }
                case "OPEN_CMD" -> {
                    Screen s = newChatScreen("/", true);
                    if (s != null) mc.setScreen(s);
                }
                case "OPEN_MENU" -> mc.setScreen(new GameMenuScreen(true));
            }
        } catch (Throwable t) {
            System.out.println("[InputSync] applyOpen failed: " + t);
        }
    }

    private static Screen newChatScreen(String initial, boolean command) {
        try {
            Constructor<ChatScreen> c = ChatScreen.class.getConstructor(String.class, boolean.class);
            return c.newInstance(initial, command);
        } catch (Throwable ignored) {}
        try {
            Constructor<ChatScreen> c = ChatScreen.class.getConstructor(String.class);
            return c.newInstance(initial);
        } catch (Throwable ignored) {}
        return null;
    }

    /* ========================================================= */
    /* ===================== APPLY: INVENTORY ================== */
    /* ========================================================= */

    private static void applyInvClick(MinecraftClient mc, String msg) {
        try {
            String[] p = msg.split("_");
            if (p.length < 5) return;

            int syncId = Integer.parseInt(p[1]);
            int slotId = Integer.parseInt(p[2]);
            int button = Integer.parseInt(p[3]);
            int typeOrd = Integer.parseInt(p[4]);

            if (mc.player == null || mc.interactionManager == null) return;
            if (!(mc.currentScreen instanceof HandledScreen<?> hs)) return;

            ScreenHandler handler = hs.getScreenHandler();
            if (handler == null || handler.syncId != syncId) return;

            SlotActionType type = SlotActionType.values()[Math.max(0, Math.min(typeOrd, SlotActionType.values().length - 1))];
            mc.interactionManager.clickSlot(syncId, slotId, button, type, mc.player);
        } catch (Throwable t) {
            System.out.println("[InputSync] applyInvClick failed: " + t);
        }
    }

    /* ========================================================= */
    /* ===================== APPLY: CHAT STATE ================= */
    /* ========================================================= */

    private static void applyChatSet(MinecraftClient mc, String msg) {
        try {
            if (!(mc.currentScreen instanceof ChatScreen)) return;
            String b64 = msg.substring("CHAT_SET_".length());
            String text = unb64(b64);
            if (text == null) text = "";

            Object tf = getChatTextField(mc);
            if (tf == null) return;

            // HARD RULE: always SET full buffer, never append.
            // STRICT: only use setText(String) if it exists; otherwise fall back to direct String field set.
            Method setText = findSetText(tf.getClass());
            if (setText != null) {
                setText.setAccessible(true);
                setText.invoke(tf, text);
            } else {
                // fallback: set the best String field (value/text)
                Field f = resolveTextValueField(tf);
                if (f == null) return;
                f.setAccessible(true);
                f.set(tf, text);
            }

            // Only move cursor if we find legit methods. NO field-clobber fallback.
            forceCursorToEndIfPossible(tf, text.length());
        } catch (Throwable t) {
            System.out.println("[InputSync] applyChatSet failed: " + t);
        }
    }

    private static void applyChatSend(MinecraftClient mc) {
        try {
            if (!(mc.currentScreen instanceof ChatScreen)) return;

            String text = readChatBuffer(mc);
            if (text == null) text = "";

            if (text.trim().isEmpty()) {
                mc.setScreen(null);
                return;
            }

            if (mc.player != null && mc.player.networkHandler != null) {
                if (text.startsWith("/")) {
                    mc.player.networkHandler.sendChatCommand(text.substring(1));
                } else {
                    mc.player.networkHandler.sendChatMessage(text);
                }
            }

            mc.setScreen(null);
        } catch (Throwable t) {
            System.out.println("[InputSync] applyChatSend failed: " + t);
        }
    }

    private static String readChatBuffer(MinecraftClient mc) {
        try {
            Object tf = getChatTextField(mc);
            if (tf == null) return null;

            Method getText = findGetText(tf.getClass());
            if (getText != null) {
                getText.setAccessible(true);
                Object v = getText.invoke(tf);
                if (v instanceof String s) return s;
            }

            // fallback: read the best String field
            Field f = resolveTextValueField(tf);
            if (f != null) {
                f.setAccessible(true);
                Object v = f.get(tf);
                if (v instanceof String s) return s;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Prefer a method literally named getText() */
    private static Method findGetText(Class<?> c) {
        try { return c.getMethod("getText"); } catch (Throwable ignored) {}
        try { return c.getDeclaredMethod("getText"); } catch (Throwable ignored) {}
        // fallback: first no-arg String-returning method
        for (Method m : c.getMethods()) {
            if ((m.getModifiers() & Modifier.STATIC) != 0) continue;
            if (m.getParameterCount() != 0) continue;
            if (m.getReturnType() != String.class) continue;
            if (m.getDeclaringClass() == Object.class) continue;
            return m;
        }
        for (Method m : c.getDeclaredMethods()) {
            if ((m.getModifiers() & Modifier.STATIC) != 0) continue;
            if (m.getParameterCount() != 0) continue;
            if (m.getReturnType() != String.class) continue;
            if (m.getDeclaringClass() == Object.class) continue;
            return m;
        }
        return null;
    }

    /**
     * STRICT: only accept setText(String).
     * Any other (String)->void method may APPEND/INSERT and causes //s//se//ser... behavior.
     */
    private static Method findSetText(Class<?> c) {
        try { return c.getMethod("setText", String.class); } catch (Throwable ignored) {}
        try { return c.getDeclaredMethod("setText", String.class); } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Resolve the String field in the text field widget that is most likely the typed text.
     * SAFE strategy: prefer a field named "text" or "value"; otherwise pick the longest current String.
     */
    private static Field resolveTextValueField(Object tf) {
        try {
            Class<?> c = tf.getClass();
            String owner = c.getName();

            if (chatTextValueField != null && owner.equals(chatTextValueOwner)) {
                return chatTextValueField;
            }

            chatTextValueOwner = owner;
            chatTextValueField = null;

            Field named = null;
            for (Class<?> cur = c; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
                for (Field f : cur.getDeclaredFields()) {
                    if ((f.getModifiers() & Modifier.STATIC) != 0) continue;
                    if (f.getType() != String.class) continue;
                    String fn = f.getName().toLowerCase(Locale.ROOT);
                    if (fn.contains("text") || fn.contains("value")) {
                        named = f;
                        break;
                    }
                }
                if (named != null) break;
            }
            if (named != null) {
                chatTextValueField = named;
                return chatTextValueField;
            }

            Field best = null;
            int bestLen = -1;

            for (Class<?> cur = c; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
                for (Field f : cur.getDeclaredFields()) {
                    if ((f.getModifiers() & Modifier.STATIC) != 0) continue;
                    if (f.getType() != String.class) continue;
                    f.setAccessible(true);
                    Object v = f.get(tf);
                    if (!(v instanceof String s)) continue;
                    if (s.length() > bestLen) {
                        bestLen = s.length();
                        best = f;
                    }
                }
            }

            chatTextValueField = best;
            return chatTextValueField;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Only use real cursor/selection methods if present. DO NOT touch fields.
     */
    private static void forceCursorToEndIfPossible(Object tf, int end) {
        try {
            Method setCursor = findIntVoidMethod(tf.getClass(), "setCursor", "setCursorPos", "setCursorPosition");
            if (setCursor != null) {
                setCursor.setAccessible(true);
                setCursor.invoke(tf, end);
            }

            Method selStart = findIntVoidMethod(tf.getClass(), "setSelectionStart");
            if (selStart != null) {
                selStart.setAccessible(true);
                selStart.invoke(tf, end);
            }

            Method selEnd = findIntVoidMethod(tf.getClass(), "setSelectionEnd");
            if (selEnd != null) {
                selEnd.setAccessible(true);
                selEnd.invoke(tf, end);
            }
        } catch (Throwable ignored) {}
    }

    private static Method findIntVoidMethod(Class<?> c, String... nameHints) {
        for (Method m : c.getMethods()) {
            if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == int.class && m.getReturnType() == void.class) {
                String n = m.getName().toLowerCase(Locale.ROOT);
                for (String hint : nameHints) {
                    if (n.contains(hint.toLowerCase(Locale.ROOT))) return m;
                }
            }
        }
        for (Method m : c.getDeclaredMethods()) {
            if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == int.class && m.getReturnType() == void.class) {
                String n = m.getName().toLowerCase(Locale.ROOT);
                for (String hint : nameHints) {
                    if (n.contains(hint.toLowerCase(Locale.ROOT))) return m;
                }
            }
        }
        return null;
    }

    /* ========================================================= */
    /* ===================== UI DEBUG (DUMPS ONLY) ============= */
    /* ========================================================= */

    private static void applyUiDebug(MinecraftClient mc, String msg) {
        Screen s = mc.currentScreen;
        if (s == null) return;

        if (!dumpedUiOnce && (msg.startsWith("UI_CLICK") || msg.startsWith("UI_KEY") || msg.startsWith("UI_CHAR"))) {
            dumpedUiOnce = true;
            dumpUiCandidates(s);
        }
        dumpPackedTypesOnceForScreen(s);

        if (msg.startsWith("UI_KEYDOWN_") || msg.startsWith("UI_KEYUP_")) {
            System.out.println("[InputSync] " + (msg.startsWith("UI_KEYDOWN_") ? "UI_KEYDOWN" : "UI_KEYUP") + " replay failed (no matching signature)");
        } else if (msg.startsWith("UI_CHAR_")) {
            System.out.println("[InputSync] UI_CHAR replay failed (no matching signature)");
        } else if (msg.startsWith("UI_CLICK_") || msg.startsWith("UI_RELEASE_")) {
            System.out.println("[InputSync] " + (msg.startsWith("UI_CLICK_") ? "UI_CLICK" : "UI_RELEASE") + " replay failed (no matching signature)");
        }
    }

    /* ========================================================= */
    /* ===================== UI DUMPS ========================== */
    /* ========================================================= */

    private static void dumpUiCandidates(Screen s) {
        try {
            System.out.println("========== [InputSync] UI CANDIDATE METHODS ==========");
            System.out.println("Screen class = " + s.getClass().getName());

            dumpCandidatesFromClass(s.getClass(), true);
            dumpCandidatesFromClass(s.getClass(), false);

            System.out.println("========== [InputSync] END UI CANDIDATE METHODS ==========");
        } catch (Throwable ignored) {}
    }

    private static void dumpCandidatesFromClass(Class<?> c, boolean pub) {
        Method[] ms = pub ? c.getMethods() : c.getDeclaredMethods();
        for (Method m : ms) {
            int pc = m.getParameterCount();
            Class<?>[] pt = m.getParameterTypes();

            boolean packed = (pc == 2 && pt[1] == boolean.class && !pt[0].isPrimitive() && m.getReturnType() == boolean.class);
            boolean legacyKey = (pc == 3 && pt[0] == int.class && pt[1] == int.class && pt[2] == int.class);
            boolean legacyChar = (pc == 2 && pt[0] == char.class && pt[1] == int.class);
            boolean moved = (pc == 2 && pt[0] == double.class && pt[1] == double.class);
            boolean scrollLegacy = (pc == 4 && pt[0] == double.class && pt[1] == double.class && pt[2] == double.class && pt[3] == double.class);

            if (packed || legacyKey || legacyChar || moved || scrollLegacy) {
                System.out.println("  " + (pub ? "" : "(decl) ") + m.toGenericString());
            }
        }
    }

    private static void dumpPackedTypesOnceForScreen(Screen s) {
        String name = s.getClass().getName();
        if (name.equals(dumpedPackedForScreen)) return;
        dumpedPackedForScreen = name;

        try {
            Method handler = getPacked2HandlerCached(s);
            if (handler == null) return;

            Class<?> packed = handler.getParameterTypes()[0];

            System.out.println("========== [InputSync] PACKED TYPE DUMP ==========");
            System.out.println("Screen = " + name);
            System.out.println("Handler = " + handler.toGenericString());
            System.out.println("PACKED = " + packed.getName());

            for (Constructor<?> c : packed.getDeclaredConstructors()) {
                System.out.println("  PCTOR " + c.toGenericString());
            }

            Class<?> payload = null;
            for (Constructor<?> c : packed.getDeclaredConstructors()) {
                Class<?>[] pt = c.getParameterTypes();
                if (pt.length == 3 && pt[0] == double.class && pt[1] == double.class && !pt[2].isPrimitive()) {
                    payload = pt[2];
                    break;
                }
            }

            if (payload != null) {
                System.out.println("PAYLOAD = " + payload.getName());

                for (Constructor<?> c : payload.getDeclaredConstructors()) {
                    System.out.println("  CTOR " + c.toGenericString());
                }

                System.out.println("-- PAYLOAD METHODS (declared) --");
                for (Method m : payload.getDeclaredMethods()) {
                    System.out.println("  " + m.toGenericString());
                }

                System.out.println("-- PAYLOAD METHODS (public) --");
                for (Method m : payload.getMethods()) {
                    if (m.getDeclaringClass() == Object.class) continue;
                    System.out.println("  " + m.toGenericString());
                }
            }

            System.out.println("========== [InputSync] END PACKED TYPE DUMP ==========");
        } catch (Throwable ignored) {}
    }

    private static Method getPacked2HandlerCached(Screen s) {
        String owner = s.getClass().getName();
        if (cachedPackedHandler != null && owner.equals(cachedPackedHandlerOwner)) return cachedPackedHandler;

        Method m = findPacked2Handler(s);
        cachedPackedHandler = m;
        cachedPackedHandlerOwner = owner;
        return m;
    }

    private static Method findPacked2Handler(Screen s) {
        for (Method m : s.getClass().getMethods()) {
            if (isPacked2Handler(m)) return m;
        }
        for (Method m : s.getClass().getDeclaredMethods()) {
            if (isPacked2Handler(m)) return m;
        }
        for (Class<?> itf : s.getClass().getInterfaces()) {
            for (Method m : itf.getMethods()) {
                if (isPacked2Handler(m)) return m;
            }
        }
        return null;
    }

    private static boolean isPacked2Handler(Method m) {
        if (m.getReturnType() != boolean.class) return false;
        if (m.getParameterCount() != 2) return false;
        Class<?>[] pt = m.getParameterTypes();
        return !pt[0].isPrimitive() && pt[1] == boolean.class;
    }

    /* ========================================================= */
    /* ===================== CHAT REFLECTION =================== */
    /* ========================================================= */

    private static Object getChatTextField(MinecraftClient mc) {
        try {
            if (!(mc.currentScreen instanceof ChatScreen cs)) return null;

            String owner = cs.getClass().getName();
            if (chatFieldOwner == null || !chatFieldOwner.equals(owner)) {
                chatFieldOwner = owner;
                chatTextFieldField = null;
                chatFieldSearched = false;

                chatTextValueField = null;
                chatTextValueOwner = null;
            }

            if (!chatFieldSearched) {
                chatFieldSearched = true;

                for (Class<?> cur = cs.getClass(); cur != null && cur != Object.class; cur = cur.getSuperclass()) {
                    for (Field f : cur.getDeclaredFields()) {
                        if ((f.getModifiers() & Modifier.STATIC) != 0) continue;
                        f.setAccessible(true);
                        Object v = f.get(cs);
                        if (v == null) continue;

                        if (looksLikeTextField(v.getClass())) {
                            chatTextFieldField = f;
                            break;
                        }
                    }
                    if (chatTextFieldField != null) break;
                }

                if (chatTextFieldField != null) {
                    System.out.println("[InputSync] Chat text field locked to: " + chatTextFieldField.getName() +
                            " -> " + chatTextFieldField.getType().getName());
                } else {
                    System.out.println("[InputSync] Chat text field NOT FOUND");
                }
            }

            if (chatTextFieldField != null) {
                chatTextFieldField.setAccessible(true);
                return chatTextFieldField.get(cs);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean looksLikeTextField(Class<?> t) {
        String n = t.getName();
        if (n.endsWith("class_342")) return true;

        // Must be able to read text
        if (findGetText(t) == null) return false;

        // If it has a real setText(String), great
        if (findSetText(t) != null) return true;

        // Otherwise allow it if it has ANY instance String field (we can safely set it directly)
        for (Class<?> cur = t; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
            for (Field f : cur.getDeclaredFields()) {
                if ((f.getModifiers() & Modifier.STATIC) != 0) continue;
                if (f.getType() == String.class) return true;
            }
        }
        return false;
    }

    /* ========================================================= */
    /* ===================== INVENTORY SLOT PICK =============== */
    /* ========================================================= */

    private static int getHoveredSlotId(HandledScreen<?> hs) {
        try {
            if (handledHoveredSlotField == null) {
                for (Class<?> cur = hs.getClass(); cur != null && cur != Object.class; cur = cur.getSuperclass()) {
                    for (Field f : cur.getDeclaredFields()) {
                        if ((f.getModifiers() & Modifier.STATIC) != 0) continue;
                        if (!Slot.class.isAssignableFrom(f.getType())) continue;
                        f.setAccessible(true);
                        handledHoveredSlotField = f;
                        break;
                    }
                    if (handledHoveredSlotField != null) break;
                }
            }

            if (handledHoveredSlotField != null) {
                handledHoveredSlotField.setAccessible(true);
                Slot sl = (Slot) handledHoveredSlotField.get(hs);
                if (sl != null) return sl.id;
            }
        } catch (Throwable ignored) {}
        return -999;
    }

    private static int slotIdFromMouse(HandledScreen<?> hs, double mouseX, double mouseY) {
        try {
            int guiLeft = getHandledGuiX(hs);
            int guiTop = getHandledGuiY(hs);

            ScreenHandler h = hs.getScreenHandler();
            if (h == null) return -999;

            double rx = mouseX - guiLeft;
            double ry = mouseY - guiTop;

            for (Slot sl : h.slots) {
                int sx = sl.x;
                int sy = sl.y;
                if (rx >= sx && rx < sx + 16 && ry >= sy && ry < sy + 16) {
                    return sl.id;
                }
            }

            return -999;
        } catch (Throwable ignored) {
            return -999;
        }
    }

    private static int getHandledGuiX(HandledScreen<?> hs) {
        if (handledXField == null) findHandledGuiFields(hs);
        try {
            if (handledXField != null) return handledXField.getInt(hs);
        } catch (Throwable ignored) {}
        return 0;
    }

    private static int getHandledGuiY(HandledScreen<?> hs) {
        if (handledYField == null) findHandledGuiFields(hs);
        try {
            if (handledYField != null) return handledYField.getInt(hs);
        } catch (Throwable ignored) {}
        return 0;
    }

    private static void findHandledGuiFields(HandledScreen<?> hs) {
        try {
            Field bestX = null, bestY = null;

            for (Class<?> cur = hs.getClass(); cur != null && cur != Object.class; cur = cur.getSuperclass()) {
                for (Field f : cur.getDeclaredFields()) {
                    if ((f.getModifiers() & Modifier.STATIC) != 0) continue;
                    if (f.getType() != int.class) continue;
                    f.setAccessible(true);

                    int v = f.getInt(hs);
                    if (v < 0 || v > 5000) continue;

                    if (bestX == null) bestX = f;
                    else if (bestY == null && f != bestX) bestY = f;

                    if (bestX != null && bestY != null) break;
                }
                if (bestX != null && bestY != null) break;
            }

            handledXField = bestX;
            handledYField = bestY;
        } catch (Throwable ignored) {}
    }

    /* ========================================================= */
    /* ===================== STATE ============================= */
    /* ========================================================= */

    /**
     * MOVE_yaw_pitch_fwd_strafe_jump_sneak_sprint_attackHeld_useHeld_hotbar
     */
    private static final class MoveState {
        final float yaw, pitch;
        final float forward, strafe;
        final boolean jump, sneak, sprint, attackHeld, useHeld;
        final int hotbar;

        private MoveState(float yaw, float pitch, float forward, float strafe,
                          boolean jump, boolean sneak, boolean sprint,
                          boolean attackHeld, boolean useHeld, int hotbar) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.forward = forward;
            this.strafe = strafe;
            this.jump = jump;
            this.sneak = sneak;
            this.sprint = sprint;
            this.attackHeld = attackHeld;
            this.useHeld = useHeld;
            this.hotbar = hotbar;
        }

        static MoveState capture(MinecraftClient mc) {
            if (mc.player == null) return null;

            float yaw = mc.player.getYaw();
            float pitch = mc.player.getPitch();

            float fwd = (mc.options.forwardKey.isPressed() ? 1.0f : 0.0f) + (mc.options.backKey.isPressed() ? -1.0f : 0.0f);
            float str = (mc.options.rightKey.isPressed() ? 1.0f : 0.0f) + (mc.options.leftKey.isPressed() ? -1.0f : 0.0f);

            boolean jump = mc.options.jumpKey.isPressed();
            boolean sneak = mc.options.sneakKey.isPressed();
            boolean sprint = mc.options.sprintKey.isPressed() || mc.player.isSprinting();

            boolean attackHeld = false;
            boolean useHeld = false;
            try {
                if (mc.currentScreen == null) {
                    long h = mc.getWindow().getHandle();
                    attackHeld = GLFW.glfwGetMouseButton(h, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
                    useHeld = GLFW.glfwGetMouseButton(h, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
                }
            } catch (Throwable ignored) {}

            int hotbar = getSelectedHotbarSlot(mc);

            return new MoveState(yaw, pitch, fwd, str, jump, sneak, sprint, attackHeld, useHeld, hotbar);
        }

        String encode() {
            return String.format(Locale.ROOT,
                    "MOVE_%f_%f_%f_%f_%d_%d_%d_%d_%d_%d",
                    yaw, pitch, forward, strafe,
                    jump ? 1 : 0,
                    sneak ? 1 : 0,
                    sprint ? 1 : 0,
                    attackHeld ? 1 : 0,
                    useHeld ? 1 : 0,
                    hotbar
            );
        }

        static MoveState parse(String msg) {
            try {
                String[] p = msg.split("_");
                return new MoveState(
                        Float.parseFloat(p[1]),
                        Float.parseFloat(p[2]),
                        Float.parseFloat(p[3]),
                        Float.parseFloat(p[4]),
                        "1".equals(p[5]),
                        "1".equals(p[6]),
                        "1".equals(p[7]),
                        "1".equals(p[8]),
                        "1".equals(p[9]),
                        Integer.parseInt(p[10])
                );
            } catch (Throwable t) {
                return null;
            }
        }

        void apply(MinecraftClient mc) {
            if (mc.player == null) return;

            mc.player.setYaw(yaw);
            mc.player.setPitch(pitch);

            setSelectedHotbarSlot(mc, hotbar);

            setKey(mc.options.forwardKey, forward > 0.0f);
            setKey(mc.options.backKey, forward < 0.0f);
            setKey(mc.options.rightKey, strafe > 0.0f);
            setKey(mc.options.leftKey, strafe < 0.0f);
            setKey(mc.options.jumpKey, jump);
            setKey(mc.options.sneakKey, sneak);

            setKey(mc.options.sprintKey, sprint);
            mc.player.setSprinting(sprint && (Math.abs(forward) > 0.0f) && !sneak);

            // Do NOT hold these keys down on followers
            setKey(mc.options.useKey, false);
            setKey(mc.options.attackKey, false);

            if (mc.currentScreen == null) {
                if (attackHeld) {
                    tickBreakProgress(mc);
                } else {
                    stopBreaking(mc);
                }

                if (useHeld && !lastUseHeld) doUsePulse(mc);
                if (attackHeld && !lastAttackHeld) doAttackPulse(mc);
            } else {
                stopBreaking(mc);
            }

            lastAttackHeld = attackHeld;
            lastUseHeld = useHeld;
        }
    }

    private static void tickBreakProgress(MinecraftClient mc) {
        try {
            if (mc.player == null || mc.interactionManager == null) return;

            HitResult hr = mc.crosshairTarget;
            if (hr instanceof BlockHitResult bhr) {
                mc.interactionManager.updateBlockBreakingProgress(bhr.getBlockPos(), bhr.getSide());
                wasBreaking = true;
            } else {
                stopBreaking(mc);
            }
        } catch (Throwable ignored) {}
    }

    private static void stopBreaking(MinecraftClient mc) {
        try {
            if (!wasBreaking) return;
            wasBreaking = false;
            if (mc.interactionManager != null) mc.interactionManager.cancelBlockBreaking();
        } catch (Throwable ignored) {}
    }

    private static void doAttackPulse(MinecraftClient mc) {
        try {
            if (mc.player == null || mc.interactionManager == null) return;
            if (mc.currentScreen != null) return;

            HitResult hr = mc.crosshairTarget;
            if (hr instanceof EntityHitResult ehr) {
                Entity e = ehr.getEntity();
                mc.interactionManager.attackEntity(mc.player, e);
                mc.player.swingHand(Hand.MAIN_HAND);
            } else {
                mc.player.swingHand(Hand.MAIN_HAND);
            }
        } catch (Throwable t) {
            System.out.println("[InputSync] doAttackPulse failed: " + t);
        }
    }

    private static void doUsePulse(MinecraftClient mc) {
        try {
            if (mc.player == null || mc.interactionManager == null) return;
            if (mc.currentScreen != null) return;

            HitResult hr = mc.crosshairTarget;

            if (hr instanceof BlockHitResult bhr) {
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr);
                mc.player.swingHand(Hand.MAIN_HAND);
                return;
            }
            if (hr instanceof EntityHitResult ehr) {
                mc.interactionManager.interactEntity(mc.player, ehr.getEntity(), Hand.MAIN_HAND);
                mc.player.swingHand(Hand.MAIN_HAND);
                return;
            }
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            mc.player.swingHand(Hand.MAIN_HAND);
        } catch (Throwable t) {
            System.out.println("[InputSync] doUsePulse failed: " + t);
        }
    }

    /* ========================================================= */
    /* ===================== HOTBAR ============================ */
    /* ========================================================= */

    private static int getSelectedHotbarSlot(MinecraftClient mc) {
        try {
            if (mc.player == null) return 0;
            Object inv = mc.player.getInventory();

            if (invSelectedSlotField == null) {
                try {
                    Field f = inv.getClass().getDeclaredField("selectedSlot");
                    f.setAccessible(true);
                    invSelectedSlotField = f;
                } catch (NoSuchFieldException e) {
                    for (Field f : inv.getClass().getDeclaredFields()) {
                        if (f.getType() != int.class) continue;
                        if ((f.getModifiers() & Modifier.STATIC) != 0) continue;
                        f.setAccessible(true);
                        int v = f.getInt(inv);
                        if (v >= 0 && v <= 8) { invSelectedSlotField = f; break; }
                    }
                }
            }

            if (invSelectedSlotField != null) {
                int v = invSelectedSlotField.getInt(inv);
                if (v >= 0 && v <= 8) return v;
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static void setSelectedHotbarSlot(MinecraftClient mc, int slot) {
        try {
            if (mc.player == null) return;
            slot = Math.max(0, Math.min(8, slot));

            Object inv = mc.player.getInventory();
            if (invSelectedSlotField == null) getSelectedHotbarSlot(mc);
            if (invSelectedSlotField != null) {
                invSelectedSlotField.setAccessible(true);
                invSelectedSlotField.setInt(inv, slot);
            }
        } catch (Throwable ignored) {}
    }

    private static void setKey(KeyBinding k, boolean down) {
        try { k.setPressed(down); } catch (Throwable ignored) {}
    }

    /* ========================================================= */
    /* ===================== BASIC HELPERS ===================== */
    /* ========================================================= */

    public static void sendToServer(String line) {
        if (out != null && connected) out.println(line);
    }

    public static boolean shouldSendNow() {
        return connected && CommandHandler.isEnabled() && isLeader &&
                !Boolean.TRUE.equals(SUPPRESS_SEND.get());
    }

    public static void suppressSending(Runnable r) {
        SUPPRESS_SEND.set(Boolean.TRUE);
        try { r.run(); } finally { SUPPRESS_SEND.set(Boolean.FALSE); }
    }

    private static void cleanupSocket() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        socket = null;
        out = null;
    }

    private static int safeParseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable ignored) { return def; }
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String b64) {
        try {
            byte[] b = Base64.getDecoder().decode(b64);
            return new String(b, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return null;
        }
    }
}


// Ported from Raven B4 -> Raven BS
// dev. M1CK3Y | BS port adjusted for Raven BS script API (ScriptDefaults bindings)
//
// === GLOBAL @SuppressWarnings workaround ==========================================
// BS compiles user scripts via javax.tools.JavaCompiler -> getTask() and routes ALL
// diagnostics (warning + note + error) through ScriptDiagnosticListener, which prints
// them as "Error loading script" messages.  The usual culprit is the infamous
// "uses unchecked or unsafe operations" compiler NOTE (not an error), but BS still
// flushes it as red debug spam and the user interprets it as a load failure.
//
// We CANNOT add @SuppressWarnings to the script CLASS (the class declaration is
// injected by Script.setCode() into its wrapper), so we inject a small marker inner
// interface carrying the annotation right at the top of user code.  Most (all) javac
// implementations propagate a top-level type's @SuppressWarning into the rest of the
// compilation unit when it's the first user-visible declaration.  To be safe we ALSO
// sprinkle @SuppressWarnings on every method body that might trigger raw/unchecked
// notes (varargs Object[], raw List usage, Reflection API on Class.forName, etc.).
@SuppressWarnings({"unchecked","rawtypes","deprecation","cast","varargs","unused","fallthrough"})
interface BS_HUD_WORKAROUND_SUPPRESS {}
// =================================================================================
//
// B4 -> BS adjustments (verified against raven-bs source at E:\OntherFiles\Developer Client\raven-bs):
//   1. getScriptName() removed -> use static `scriptName` field injected by Script.setCode()
//   2. registerSlider(name, suffix, def, arr) NOT a BS overload
//      -> use registerSlider(String name, int defaultIndex, String[] arr)  [ScriptDefaults:866]
//   3. modules.getSlider(m,n) returns DOUBLE in BS -> int cast where needed  [ScriptDefaults:931]
//   4. render.text(...) NOT in BS -> use render.text2d(text, x, y, scale, color, shadow) [ScriptDefaults:1352]
//   5. render.rect(...) signature identical in BS, keep calls as-is.
//   6. BS Settings module has NO named "Default theme" slider. Use keystrokesmod.utility.Theme
//      as the global fallback when "Default" is picked in the Theme slider.
//   7. Time formatting: NEVER use java.text.SimpleDateFormat / java.util.Date inside the
//      script body (no imports added, and Script SecureClassLoader blocks unimported FQCN
//      lookup at runtime -> NoClassDefFoundError). We compute HH:mm:ss manually using
//      integer math on client.time() milliseconds.
//   8. FPS: blend manual frame counter with official BS API `client.getFPS()`.
//   9. End of OneTap watermark text changed from "raven b4" -> "raven bs".

int MAX_HITS = 20;
String[] hitTarget = new String[MAX_HITS];
String[] hitHitbox = new String[MAX_HITS];
String[] hitDamage = new String[MAX_HITS];
String[] hitHealth = new String[MAX_HITS];
long[] hitTime = new long[MAX_HITS];
float[] hitY = new float[MAX_HITS];
boolean[] hitActive = new boolean[MAX_HITS];

long lastFPSUpdate = 0;
int frames = 0;
int currentFPS = 0;

int currentPing = 0;
long lastPingUpdate = 0;

// 0 = None, 1 = gamesense, 2 = OneTap (same order as B4; shared between Watermark & Hitlog style)
String[] watermarkStyles = {"None", "gamesense", "OneTap"};
String[] animTimeOptions = {"\u00A7cDisabled", "\u00A7b0.1s", "\u00A7b0.2s", "\u00A7b0.3s", "\u00A7b0.4s", "\u00A7b0.5s",
                             "\u00A7b0.6s", "\u00A7b0.7s", "\u00A7b0.8s", "\u00A7b0.9s", "\u00A7b1.0s"};
String[] fakeFpsOptions;
// Index 0 = "Default" -> follow Raven BS global Theme (Theme enum); rest B4-compatible themes
String[] themeOptions = {
    "Default","Gamesense","Rainbow","Aurora","Cherry","Cotton Candy",
    "Flare","Flower","Forest","Frost","Gold",
    "Grayscale","Inferno","Royal","Sandstorm","Sky","Vine"
};

// @SuppressWarnings applied at each method boundary kills 100% of "uses unchecked or unsafe
// operations" warnings the BS ScriptDiagnosticListener reprints as red errors even though
// they're just javac WARNING notes.  (BS compiler treats them as non-fatal but the
// diagnostic listener blasts every diagnostic regardless of kind.)
@SuppressWarnings({"unchecked","rawtypes","deprecation","cast"})
// Pad helper used by manual clock formatter ONLY (no java.text imports anywhere)
String pad2(int n) {
    if (n < 0) n = 0;
    if (n > 99) n = n % 100;
    if (n < 10) return "0" + n;
    return "" + n;
}

@SuppressWarnings({"unchecked","rawtypes","deprecation"})
String formatClockHMS(long millis) {
    long total = millis / 1000L;          // seconds since epoch
    long daySec = total % 86400L;          // seconds into current day
    int h = (int) (daySec / 3600L);
    int m = (int) ((daySec % 3600L) / 60L);
    int s = (int) (daySec % 60L);
    // Local timezone offset approximation: convert "UTC wall clock" from millis to
    // local wall clock via TimeZone default offset (TimeZone is imported via the
    // pre-injected broad java.util imports? it isn't - play safe: use raw formula).
    // Raven BS runs under the JVM's default locale; to get true local time we need
    // TimeZone.getDefault().getOffset(millis).  Since TimeZone is java.util and
    // the default java.util.* import IS NOT injected, fetch offset via reflection
    // would get blocked by SecureClassLoader.  Safer fallback: use UTC+offset by
    // reading user property "user.timezone".  Even safer: the common expected
    // behavior for "show current time" is local wall clock. Use java.util.Calendar
    // via its FQCN in a try/catch, and on NoClassDefFoundError fall back to UTC.
    try {
        // Class.forName resolution (Calendar is java.util).  Script.java's setCode
        // does NOT import java.util, but java.util.* classes can still be resolved
        // at runtime by FQCN because they are on the system classloader path (the
        // SecureClassLoader is a URLClassLoader parented by Launch.classLoader whose
        // parent is the app/system loader - java.* is bootclassloader so always OK).
        // Actually java.* packages are boot loader reachable, so Calendar FQCN works
        // even without an import statement.
        Class calCls = Class.forName("java.util.Calendar");
        Object cal = calCls.getMethod("getInstance").invoke(null);
        int hh = ((Integer) calCls.getMethod("get", int.class).invoke(cal, new Integer(11))).intValue();
        int mm = ((Integer) calCls.getMethod("get", int.class).invoke(cal, new Integer(12))).intValue();
        int ss = ((Integer) calCls.getMethod("get", int.class).invoke(cal, new Integer(13))).intValue();
        return pad2(hh) + ":" + pad2(mm) + ":" + pad2(ss);
    } catch (Throwable t) {
        // If FQCN loading also fails (strict future revision of BS), show UTC time.
        return pad2(h) + ":" + pad2(m) + ":" + pad2(s);
    }
}

@SuppressWarnings({"unchecked","rawtypes","deprecation"})
void onLoad() {
    fakeFpsOptions = new String[50];
    fakeFpsOptions[0] = "\u00A7cDisabled";
    for (int i = 1; i <= 49; i++) {
        fakeFpsOptions[i] = "\u00A7a" + (i + 1) + "x";
    }

    modules.registerDescription("v1.0.1 (BS port) dev. M1CK3Y");
    modules.registerSlider("Theme", 0, themeOptions);
    modules.registerSlider("Watermark Style", 0, watermarkStyles);
    modules.registerSlider("Animation Time", 0, animTimeOptions);
    modules.registerSlider("Fake FPS", 0, fakeFpsOptions);
    modules.registerSlider("Hitlog", 0, watermarkStyles);
}

@SuppressWarnings({"unchecked","rawtypes","deprecation"})
void onEnable() {
    for (int i = 0; i < MAX_HITS; i++) hitActive[i] = false;
    frames = 0;
    currentFPS = 0;
    lastFPSUpdate = client.time();
}

int clampInt(int value, int min, int max) {
    return value < min ? min : value > max ? max : value;
}

@SuppressWarnings({"unchecked","rawtypes","deprecation"})
void addHit(String target, String hitbox, String damage, String health) {
    long now = client.time();
    for (int i = MAX_HITS - 1; i > 0; i--) {
        hitTarget[i] = hitTarget[i-1];
        hitHitbox[i] = hitHitbox[i-1];
        hitDamage[i] = hitDamage[i-1];
        hitHealth[i] = hitHealth[i-1];
        hitTime[i] = hitTime[i-1];
        hitY[i] = hitY[i-1];
        hitActive[i] = hitActive[i-1];
    }
    hitTarget[0] = target;
    hitHitbox[0] = hitbox;
    hitDamage[0] = damage;
    hitHealth[0] = health;
    hitTime[0] = now;
    hitY[0] = -15.0f;
    hitActive[0] = true;
}

int MAX_PENDING = 10;
Entity[] pendingEntity = new Entity[MAX_PENDING];
String[] pendingName = new String[MAX_PENDING];
String[] pendingHitbox = new String[MAX_PENDING];
float[] pendingHealth = new float[MAX_PENDING];
long[] pendingTime = new long[MAX_PENDING];

@SuppressWarnings({"unchecked","rawtypes","deprecation"})
void addPendingHit(Entity target, String name, String hitbox, float health) {
    long now = client.time();
    for (int i = 0; i < MAX_PENDING; i++) {
        if (pendingEntity[i] != null && pendingEntity[i] == target) {
            pendingHitbox[i] = hitbox;
            pendingTime[i] = now;
            return;
        }
    }
    for (int i = MAX_PENDING - 1; i > 0; i--) {
        pendingEntity[i] = pendingEntity[i-1];
        pendingName[i] = pendingName[i-1];
        pendingHitbox[i] = pendingHitbox[i-1];
        pendingHealth[i] = pendingHealth[i-1];
        pendingTime[i] = pendingTime[i-1];
    }
    pendingEntity[0] = target;
    pendingName[0] = name;
    pendingHitbox[0] = hitbox;
    pendingHealth[0] = health;
    pendingTime[0] = now;
}

@SuppressWarnings({"unchecked","rawtypes","deprecation"})
void checkPendingHits() {
    long now = client.time();
    for (int i = 0; i < MAX_PENDING; i++) {
        Entity target = pendingEntity[i];
        if (target != null) {
            if (now - pendingTime[i] > 500) {
                addHit(pendingName[i], pendingHitbox[i], "2.0", String.format("%.1f", new Object[]{new Float(pendingHealth[i] - 2.0f)}));
                for (int j = 0; j < MAX_PENDING; j++) {
                    if (pendingEntity[j] == target) pendingEntity[j] = null;
                }
                continue;
            }

            try {
                float currentHealth = target.getHealth();
                float damage = pendingHealth[i] - currentHealth;

                if (damage > 0 && damage < 20.0f) {
                    addHit(pendingName[i], pendingHitbox[i],
                           String.format("%.1f", new Object[]{new Float(damage)}),
                           String.format("%.1f", new Object[]{new Float(currentHealth)}));
                    for (int j = 0; j < MAX_PENDING; j++) {
                        if (pendingEntity[j] == target) pendingEntity[j] = null;
                    }
                } else if (damage < 0) {
                    pendingHealth[i] = currentHealth;
                }
            } catch (Exception e) {
                addHit(pendingName[i], pendingHitbox[i], "2.0",
                       String.format("%.1f", new Object[]{new Float(pendingHealth[i] - 2.0f)}));
                for (int j = 0; j < MAX_PENDING; j++) {
                    if (pendingEntity[j] == target) pendingEntity[j] = null;
                }
            }
        }
    }
}

// BS hook signature: boolean onPacketSent(CPacket p) -> return false cancels packet
boolean onPacketSent(CPacket packet) {
    int hitlogStyle = 0;
    try { hitlogStyle = (int) modules.getSlider(scriptName, "Hitlog"); } catch(Exception e){}
    if (hitlogStyle > 0 && packet instanceof C02) {
        try {
            C02 attack = (C02) packet;
            if ("ATTACK".equals(attack.action)) {
                String tName = "Enemy";
                float tHealth = 20.0f;
                String hitbox = "body";

                Entity target = null;
                try { target = attack.entity; } catch (Exception e) {}

                if (target != null) {
                    try { tName = target.getName(); } catch(Exception e) {}
                    try { tHealth = target.getHealth(); } catch(Exception e) {}

                    Entity player = client.getPlayer();
                    if (player != null) {
                        try {
                            float pY = (float) player.getPosition().y;
                            float tY = (float) target.getPosition().y;
                            float yDiff = tY - pY;
                            if (yDiff > 0.2f) hitbox = "head";
                            else if (yDiff < -0.8f) hitbox = "leg";
                        } catch (Exception e) {}
                    }

                    addPendingHit(target, tName, hitbox, tHealth);
                } else {
                    addHit(tName, hitbox, "2.0", "18.0");
                }
            }
        } catch (Exception e) {}
    }
    return true;
}

void updateFPS() {
    frames++;
    long now = client.time();
    if (now - lastFPSUpdate >= 1000) {
        int officialFPS = 60;
        try { officialFPS = client.getFPS(); } catch (Exception e) {}
        currentFPS = (frames + officialFPS) / 2;
        frames = 0;
        lastFPSUpdate = now;
    }
}

void updatePing() {
    long now = client.time();
    if (now - lastPingUpdate < 1000) return;
    lastPingUpdate = now;

    try {
        Entity player = client.getPlayer();
        if (player != null) {
            String myName = player.getName();
            if (myName != null) {
                // Fully-qualified parameterized List<Object> kills the raw-type
                // "unchecked or unsafe operations" warning at its source.
                @SuppressWarnings("unchecked")
                java.util.List<Object> players = (java.util.List<Object>) (java.util.List) world.getNetworkPlayers();
                if (players != null) {
                    for (Object obj : players) {
                        NetworkPlayer p = (NetworkPlayer) obj;
                        if (myName.equals(p.getName())) {
                            currentPing = p.getPing();
                            return;
                        }
                    }
                }
            }
        }
    } catch (Exception e) {}
}

int lerpColor(int c1, int c2, double t) {
    int r = clampInt((int)(((c1 >> 16) & 0xFF) + ((((c2 >> 16) & 0xFF) - ((c1 >> 16) & 0xFF)) * t)), 0, 255);
    int g = clampInt((int)(((c1 >> 8)  & 0xFF) + ((((c2 >> 8)  & 0xFF) - ((c1 >> 8)  & 0xFF)) * t)), 0, 255);
    int b = clampInt((int)(((c1)       & 0xFF) + ((((c2)       & 0xFF) - ((c1)       & 0xFF)) * t)), 0, 255);
    return 0xFF000000 | (r << 16) | (g << 8) | b;
}

String[] settingsThemeMap = {
    "Rainbow","Aurora","Cherry","Cotton candy","Flare","Flower","Forest","Frost",
    "Gold","Grayscale","Inferno","Royal","Sandstorm","Sky","Vine"
};

int getThemeColor(long ms) {
    String lo = "";
    int localIdx = 0;
    try { localIdx = (int) modules.getSlider(scriptName, "Theme"); } catch(Exception e){}

    if (localIdx == 0) {
        // "Default" -> use Raven BS global Theme (index 0 = Rainbow chroma)
        // Theme.getGradient automatically uses Settings.offset / timeMultiplier.
        try {
            int raw = keystrokesmod.utility.Theme.getGradient(0, ms * 0.05);
            return 0xFF000000 | (raw & 0xFFFFFF);
        } catch (Throwable t) {
            return 0xFFB8BEC4;
        }
    } else if (localIdx >= 1 && localIdx < themeOptions.length) {
        lo = themeOptions[localIdx].toLowerCase().trim();
    }

    if (lo.equals("rainbow")) {
        double t = ms / 420.0;
        return 0xFF000000
            | (clampInt((int)(128 + 127 * Math.sin(t)),         0, 255) << 16)
            | (clampInt((int)(128 + 127 * Math.sin(t + 2.094)), 0, 255) << 8)
            |  clampInt((int)(128 + 127 * Math.sin(t + 4.189)), 0, 255);
    }
    double p = (Math.sin(ms / 1200.0) + 1.0) / 2.0;
    if (lo.equals("aurora"))       return lerpColor(0xFF7301C2, 0xFF17F0B1, p);
    if (lo.equals("cherry"))       return lerpColor(0xFFDD3D69, 0xFFE0B3B7, p);
    if (lo.equals("cotton candy")) return lerpColor(0xFF92DAE8, 0xFFED68B8, p);
    if (lo.equals("flare"))        return lerpColor(0xFFF26B16, 0xFFE4A61D, p);
    if (lo.equals("flower"))       return lerpColor(0xFFC89AD8, 0xFFAC59B9, p);
    if (lo.equals("forest"))       return lerpColor(0xFF1F7617, 0xFF60A623, p);
    if (lo.equals("frost"))        return lerpColor(0xFFDFE3E3, 0xFFBCC5CA, p);
    if (lo.equals("gold"))         return lerpColor(0xFFE5DF30, 0xFFDADAB6, p);
    if (lo.equals("grayscale"))    return lerpColor(0xFF616368, 0xFFE7E8EA, p);
    if (lo.equals("inferno"))      return lerpColor(0xFF350000, 0xFFC03912, p);
    if (lo.equals("royal"))        return lerpColor(0xFF85BFE8, 0xFF1D3D87, p);
    if (lo.equals("sandstorm"))    return lerpColor(0xFF9D9369, 0xFFF5E3B4, p);
    if (lo.equals("sky"))          return lerpColor(0xFF81EAF8, 0xFF15BCD3, p);
    if (lo.equals("vine"))         return lerpColor(0xFF27E439, 0xFF9AF8A1, p);
    if (lo.equals("gamesense"))    return 0xFF95B806;
    return 0xFFB8BEC4;
}

String currentIP = "Singleplayer";
double currentBPS = 0.0;
String currentTime = "00:00:00";
long lastExtraUpdate = 0;

long gsAnimStartTime = 0;
float gsStartWidth = 0;
float gsTargetWidthState = 0;
float gsAnimatedWidth = 0;

float[] gsAnimX = new float[10];
float[] gsStartX = new float[10];
float[] gsTargetX = new float[10];
long[] gsStartTime = new long[10];

long otAnimStartTime = 0;
float otStartWidth = 0;
float otTargetWidthState = 0;
float otAnimatedWidth = 0;

float[] otAnimX = new float[15];
float[] otStartX = new float[15];
float[] otTargetX = new float[15];
long[] otStartTime = new long[15];

void updateExtraStats() {
    long now = client.time();
    if (now - lastExtraUpdate >= 100) {
        lastExtraUpdate = now;
        try {
            String ip = client.getServerIP();
            currentIP = (ip != null && !ip.isEmpty()) ? ip : "Singleplayer";
        } catch(Exception e) {
            currentIP = "Singleplayer";
        }

        // No SimpleDateFormat / no java.util.Date anywhere - both trigger
        // NoClassDefFoundError because Script.setCode() does NOT inject
        // java.text.* imports, and BS SecureClassLoader denies FQCN lookup
        // for non-bootclassloader namespaces.
        // Fallback chain: 1) java.util.Calendar via FQCN (boot loader, OK),
        // 2) manual UTC HH:MM:SS from millis if Calendar fails.
        try {
            currentTime = formatClockHMS(now);
        } catch (Throwable t) {
            // Worst-case: manual UTC.
            long secs = now / 1000L;
            long daySec = secs % 86400L;
            int hh = (int)(daySec / 3600L);
            int mm = (int)((daySec % 3600L) / 60L);
            int ss = (int)(daySec % 60L);
            currentTime = pad2(hh) + ":" + pad2(mm) + ":" + pad2(ss);
        }

        try {
            Entity player = client.getPlayer();
            if (player != null) {
                Vec3 pos = player.getPosition();
                Vec3 last = player.getLastPosition();
                if (pos != null && last != null) {
                    double dx = pos.x - last.x;
                    double dz = pos.z - last.z;
                    currentBPS = Math.sqrt(dx * dx + dz * dz) * 20.0;
                }
            }
        } catch(Exception e) {}
    }
}

void onRenderTick(float partialTicks) {
    updateFPS();
    updatePing();
    updateExtraStats();
    checkPendingHits();

    int style = 0;
    try { style = (int) modules.getSlider(scriptName, "Watermark Style"); } catch(Exception e) {}
    if (style == 1) drawGamesenseWatermark();
    else if (style == 2) drawOnetapWatermark();

    int hitlogStyle = 0;
    try { hitlogStyle = (int) modules.getSlider(scriptName, "Hitlog"); } catch(Exception e) {}
    if (hitlogStyle > 0) {
        drawHitlog();
    }
}

void drawGamesenseWatermark() {
    long ms = client.time();
    int fontHeight = (int) render.getFontHeight();

    String t1a = "game";
    String t1b = "sense";

    int fakeFpsMult = 0;
    try { fakeFpsMult = (int) modules.getSlider(scriptName, "Fake FPS"); } catch(Exception e) {}
    int displayFPS = currentFPS;
    if (fakeFpsMult > 0) displayFPS *= (fakeFpsMult + 1);

    String t2 = String.valueOf(displayFPS);
    String t3 = "FPS";
    String t4 = String.valueOf(currentPing);
    String t5 = "PING";
    String t6 = currentIP;
    String t7 = String.format("%.0f", new Object[]{new Double(currentBPS * 100.0)});
    String t8 = "SPEED";
    String t9 = currentTime;

    int w1a = (int) render.getFontWidth(t1a);
    int w1b = (int) render.getFontWidth(t1b);
    int w2 = (int) render.getFontWidth(t2);
    int w3 = (int) (render.getFontWidth(t3) * 0.5f);
    int w4 = (int) render.getFontWidth(t4);
    int w5 = (int) (render.getFontWidth(t5) * 0.5f);
    int w6 = (int) render.getFontWidth(t6);
    int w7 = (int) render.getFontWidth(t7);
    int w8 = (int) (render.getFontWidth(t8) * 0.5f);
    int w9 = (int) render.getFontWidth(t9);

    int gap = (int) render.getFontWidth("    ");
    int smallGap = 1;

    int textWidth = w1a + w1b + gap + w2 + smallGap + w3 + gap + w4 + smallGap + w5
                  + gap + w6 + gap + w7 + smallGap + w8 + gap + w9;

    int padding = 5;
    int gradLen = 20;
    int screenMargin = 5;

    int animIdx = 0;
    try { animIdx = (int) modules.getSlider(scriptName, "Animation Time"); } catch(Exception e) {}
    float animTime = animIdx * 100.0f;

    int targetWidth = textWidth + padding * 2;

    if (gsTargetWidthState != targetWidth) {
        gsStartWidth = (gsAnimatedWidth == 0) ? targetWidth : gsAnimatedWidth;
        gsTargetWidthState = targetWidth;
        gsAnimStartTime = client.time();
    }

    if (animTime <= 0) {
        gsAnimatedWidth = targetWidth;
    } else {
        float progress = (client.time() - gsAnimStartTime) / animTime;
        if (progress > 1.0f) progress = 1.0f;
        float ease = 1.0f - (1.0f - progress) * (1.0f - progress);
        gsAnimatedWidth = gsStartWidth + (gsTargetWidthState - gsStartWidth) * ease;
    }

    int width = (int) gsAnimatedWidth;
    int[] display = client.getDisplaySize();
    int screenWidth = (display != null && display.length > 0) ? display[0] : 1000;
    int x = screenWidth - screenMargin - width - gradLen;
    int y = 5;
    int height = fontHeight + 6;

    int bgAlpha = 80;
    int lineAlpha = 130;

    render.rect(x, y, x + width, y + height, (bgAlpha << 24) | 0x000000);
    render.rect(x, y, x + width, y + 1, (lineAlpha << 24) | 0x000000);
    render.rect(x, y + height - 1, x + width, y + height, (lineAlpha << 24) | 0x000000);

    for (int i = 0; i < gradLen; i++) {
        float ratioL = i / (float)(gradLen - 1);
        float fadeL = (float) Math.pow(ratioL, 1.5);
        int aBgL = (int) (25 + (bgAlpha - 25) * fadeL);
        int aLineL = (int) (lineAlpha * fadeL);

        int lx = x - gradLen + i;
        render.rect(lx, y, lx + 1, y + height, (aBgL << 24) | 0x000000);
        if (aLineL > 0) {
            render.rect(lx, y, lx + 1, y + 1, (aLineL << 24) | 0x000000);
            render.rect(lx, y + height - 1, lx + 1, y + height, (aLineL << 24) | 0x000000);
        }

        float ratioR = 1.0f - (i / (float)(gradLen - 1));
        float fadeR = (float) Math.pow(ratioR, 1.5);
        int aBgR = (int) (25 + (bgAlpha - 25) * fadeR);
        int aLineR = (int) (lineAlpha * fadeR);

        int rx = x + width + i;
        render.rect(rx, y, rx + 1, y + height, (aBgR << 24) | 0x000000);
        if (aLineR > 0) {
            render.rect(rx, y, rx + 1, y + 1, (aLineR << 24) | 0x000000);
            render.rect(rx, y + height - 1, rx + 1, y + height, (aLineR << 24) | 0x000000);
        }
    }

    int currentRx = (x + width) - padding;
    int[] w = {w1a, w1b, w2, w3, w4, w5, w6, w7, w8, w9};
    String[] t = {t1a, t1b, t2, t3, t4, t5, t6, t7, t8, t9};
    int[] gaps = {0, 0, gap, smallGap, gap, smallGap, gap, gap, smallGap, gap};

    float[] targetRightX = new float[10];
    for (int i = 9; i >= 0; i--) {
        targetRightX[i] = currentRx;
        currentRx -= w[i];
        currentRx -= gaps[i];
    }

    long now = client.time();
    int textY = y + 4;
    float smallY = textY + (fontHeight * 0.5f) - 1.0f;

    for (int i = 0; i < 10; i++) {
        if (gsTargetX[i] != targetRightX[i]) {
            gsStartX[i] = (gsAnimX[i] == 0) ? targetRightX[i] : gsAnimX[i];
            gsTargetX[i] = targetRightX[i];
            gsStartTime[i] = now;
        }

        if (animTime <= 0) {
            gsAnimX[i] = targetRightX[i];
        } else {
            float p = (now - gsStartTime[i]) / animTime;
            if (p > 1.0f) p = 1.0f;
            float easeT = 1.0f - (1.0f - p) * (1.0f - p);
            gsAnimX[i] = gsStartX[i] + (gsTargetX[i] - gsStartX[i]) * easeT;
        }
    }
    int themeColor = getThemeColor(now);

    render.text2d(t[0], (int)gsAnimX[0] - w[0], textY, 1.0f, 0xFFFFFFFF, true);
    render.text2d(t[1], (int)gsAnimX[1] - w[1], textY, 1.0f, themeColor, true);
    render.text2d(t[2], (int)gsAnimX[2] - w[2], textY, 1.0f, themeColor, true);
    render.text2d(t[3], (int)gsAnimX[3] - w[3], smallY, 0.5f, 0xFFFFFFFF, true);
    render.text2d(t[4], (int)gsAnimX[4] - w[4], textY, 1.0f, themeColor, true);
    render.text2d(t[5], (int)gsAnimX[5] - w[5], smallY, 0.5f, 0xFFFFFFFF, true);
    render.text2d(t[6], (int)gsAnimX[6] - w[6], textY, 1.0f, 0xFFFFFFFF, true);
    render.text2d(t[7], (int)gsAnimX[7] - w[7], textY, 1.0f, themeColor, true);
    render.text2d(t[8], (int)gsAnimX[8] - w[8], smallY, 0.5f, 0xFFFFFFFF, true);
    render.text2d(t[9], (int)gsAnimX[9] - w[9], textY, 1.0f, 0xFFFFFFFF, true);
}

void drawOnetapWatermark() {
    long ms = client.time();
    int fontHeight = (int) render.getFontHeight();

    String o1 = "onetap | ";

    int fakeFpsMult = 0;
    try { fakeFpsMult = (int) modules.getSlider(scriptName, "Fake FPS"); } catch(Exception e) {}
    int displayFPS = currentFPS;
    if (fakeFpsMult > 0) displayFPS *= (fakeFpsMult + 1);

    String o2 = String.valueOf(displayFPS);
    String o3 = "FPS";
    String o4 = " | ";
    String o5 = String.valueOf(currentPing);
    String o6 = "PING";
    String o7 = " | ";
    String o8 = currentIP;
    String o9 = " | ";
    String o10 = String.format("%.0f", new Object[]{new Double(currentBPS * 100.0)});
    String o11 = "SPEED";
    String o12 = " | ";
    String o13 = currentTime;
    String o14 = " | raven bs";

    int w1 = (int) render.getFontWidth(o1);
    int w2 = (int) render.getFontWidth(o2);
    int w3 = (int) (render.getFontWidth(o3) * 0.5f);
    int w4 = (int) render.getFontWidth(o4);
    int w5 = (int) render.getFontWidth(o5);
    int w6 = (int) (render.getFontWidth(o6) * 0.5f);
    int w7 = (int) render.getFontWidth(o7);
    int w8 = (int) render.getFontWidth(o8);
    int w9 = (int) render.getFontWidth(o9);
    int w10 = (int) render.getFontWidth(o10);
    int w11 = (int) (render.getFontWidth(o11) * 0.5f);
    int w12 = (int) render.getFontWidth(o12);
    int w13 = (int) render.getFontWidth(o13);
    int w14 = (int) render.getFontWidth(o14);

    int smallGap = 1;
    int textWidth = w1 + w2 + smallGap + w3 + w4 + w5 + smallGap + w6 + w7 + w8 + w9
                  + w10 + smallGap + w11 + w12 + w13 + w14;

    int padding = 5;
    int gradLen = 20;
    int screenMargin = 5;

    int animIdx = 0;
    try { animIdx = (int) modules.getSlider(scriptName, "Animation Time"); } catch(Exception e) {}
    float animTime = animIdx * 100.0f;

    int targetWidth = textWidth + padding * 2;

    if (otTargetWidthState != targetWidth) {
        otStartWidth = (otAnimatedWidth == 0) ? targetWidth : otAnimatedWidth;
        otTargetWidthState = targetWidth;
        otAnimStartTime = client.time();
    }

    if (animTime <= 0) {
        otAnimatedWidth = targetWidth;
    } else {
        float progress = (client.time() - otAnimStartTime) / animTime;
        if (progress > 1.0f) progress = 1.0f;
        float ease = 1.0f - (1.0f - progress) * (1.0f - progress);
        otAnimatedWidth = otStartWidth + (otTargetWidthState - otStartWidth) * ease;
    }

    int width = (int) otAnimatedWidth;
    int[] display = client.getDisplaySize();
    int screenWidth = (display != null && display.length > 0) ? display[0] : 1000;
    int x = screenWidth - screenMargin - width - gradLen;
    int y = 5;
    int height = fontHeight + 6;

    int bgAlpha = 80;
    int lineAlpha = 130;

    render.rect(x, y, x + width, y + height, (bgAlpha << 24) | 0x111111);
    render.rect(x, y + height - 1, x + width, y + height, (lineAlpha << 24) | 0x000000);

    for (int i = 0; i < gradLen; i++) {
        float ratioL = i / (float)(gradLen - 1);
        float fadeL = (float) Math.pow(ratioL, 1.5);
        int aBgL = (int) (bgAlpha * fadeL);
        int aLineL = (int) (lineAlpha * fadeL);
        if (aBgL <= 2) { aBgL = 0; aLineL = 0; }

        int lx = x - gradLen + i;
        if (aBgL > 0) render.rect(lx, y, lx + 1, y + height, (aBgL << 24) | 0x111111);
        if (aLineL > 0) render.rect(lx, y + height - 1, lx + 1, y + height, (aLineL << 24) | 0x000000);

        float ratioR = 1.0f - (i / (float)(gradLen - 1));
        float fadeR = (float) Math.pow(ratioR, 1.5);
        int aBgR = (int) (bgAlpha * fadeR);
        int aLineR = (int) (lineAlpha * fadeR);
        if (aBgR <= 2) { aBgR = 0; aLineR = 0; }

        int rx = x + width + i;
        if (aBgR > 0) render.rect(rx, y, rx + 1, y + height, (aBgR << 24) | 0x111111);
        if (aLineR > 0) render.rect(rx, y + height - 1, rx + 1, y + height, (aLineR << 24) | 0x000000);
    }

    int totalWidth = width + gradLen * 2;
    for (int i = 0; i < totalWidth; i++) {
        int color = getThemeColor(ms - i * 15);
        int alpha = 255;
        if (i < gradLen) {
            float r = i / (float)(gradLen - 1);
            alpha = (int)(255 * Math.pow(r, 1.5));
        } else if (i >= totalWidth - gradLen) {
            float r = 1.0f - (i - (totalWidth - gradLen)) / (float)(gradLen - 1);
            alpha = (int)(255 * Math.pow(r, 1.5));
        }
        if (alpha > 2) {
            int cx = x - gradLen + i;
            render.rect(cx, y, cx + 1, y + 1, (alpha << 24) | (color & 0xFFFFFF));
        }
    }

    int currentRx = (x + width) - padding;
    int[] w = {w1, w2, w3, w4, w5, w6, w7, w8, w9, w10, w11, w12, w13, w14};
    String[] t = {o1, o2, o3, o4, o5, o6, o7, o8, o9, o10, o11, o12, o13, o14};
    int[] gaps = {0, 0, smallGap, 0, 0, smallGap, 0, 0, 0, 0, smallGap, 0, 0, 0};

    float[] targetRightX = new float[14];
    for (int i = 13; i >= 0; i--) {
        targetRightX[i] = currentRx;
        currentRx -= w[i];
        currentRx -= gaps[i];
    }

    long now = client.time();
    int textY = y + 4;
    float smallY = textY + (fontHeight * 0.5f) - 1.0f;

    for (int i = 0; i < 14; i++) {
        if (otTargetX[i] != targetRightX[i]) {
            otStartX[i] = (otAnimX[i] == 0) ? targetRightX[i] : otAnimX[i];
            otTargetX[i] = targetRightX[i];
            otStartTime[i] = now;
        }

        if (animTime <= 0) {
            otAnimX[i] = targetRightX[i];
        } else {
            float p = (now - otStartTime[i]) / animTime;
            if (p > 1.0f) p = 1.0f;
            float easeT = 1.0f - (1.0f - p) * (1.0f - p);
            otAnimX[i] = otStartX[i] + (otTargetX[i] - otStartX[i]) * easeT;
        }
    }

    int themeColor = getThemeColor(now);

    render.text2d(t[0],  (int)otAnimX[0]  - w[0],  textY, 1.0f, 0xFFFFFFFF, true);
    render.text2d(t[1],  (int)otAnimX[1]  - w[1],  textY, 1.0f, themeColor, true);
    render.text2d(t[2],  (int)otAnimX[2]  - w[2],  smallY, 0.5f, 0xFFFFFFFF, true);
    render.text2d(t[3],  (int)otAnimX[3]  - w[3],  textY, 1.0f, 0xFFFFFFFF, true);
    render.text2d(t[4],  (int)otAnimX[4]  - w[4],  textY, 1.0f, themeColor, true);
    render.text2d(t[5],  (int)otAnimX[5]  - w[5],  smallY, 0.5f, 0xFFFFFFFF, true);
    render.text2d(t[6],  (int)otAnimX[6]  - w[6],  textY, 1.0f, 0xFFFFFFFF, true);
    render.text2d(t[7],  (int)otAnimX[7]  - w[7],  textY, 1.0f, 0xFFFFFFFF, true);
    render.text2d(t[8],  (int)otAnimX[8]  - w[8],  textY, 1.0f, 0xFFFFFFFF, true);
    render.text2d(t[9],  (int)otAnimX[9]  - w[9],  textY, 1.0f, themeColor, true);
    render.text2d(t[10], (int)otAnimX[10] - w[10], smallY, 0.5f, 0xFFFFFFFF, true);
    render.text2d(t[11], (int)otAnimX[11] - w[11], textY, 1.0f, 0xFFFFFFFF, true);
    render.text2d(t[12], (int)otAnimX[12] - w[12], textY, 1.0f, 0xFFFFFFFF, true);
    render.text2d(t[13], (int)otAnimX[13] - w[13], textY, 1.0f, 0xFFFFFFFF, true);
}

void drawHitlog() {
    long now = client.time();
    int fontHeight = (int) render.getFontHeight();
    int hitlogHeight = fontHeight + 6;
    int spacing = 5;

    int style = 0;
    try { style = (int) modules.getSlider(scriptName, "Hitlog"); } catch(Exception e) {}
    int animIdx = 0;
    try { animIdx = (int) modules.getSlider(scriptName, "Animation Time"); } catch(Exception e) {}
    float animTime = animIdx * 100.0f;

    int activeIndex = 0;
    for (int i = 0; i < MAX_HITS; i++) {
        if (!hitActive[i]) continue;

        long age = now - hitTime[i];
        if (age > 3000) {
            hitActive[i] = false;
            continue;
        }

        float progressIn = 1.0f;
        float progressOut = 1.0f;

        if (animTime > 0) {
            if (age < animTime) {
                progressIn = age / animTime;
            }
            if (age > 3000 - animTime) {
                progressOut = (3000 - age) / animTime;
            }
        }
        if (progressIn < 0.0f) progressIn = 0.0f;
        if (progressIn > 1.0f) progressIn = 1.0f;
        if (progressOut < 0.0f) progressOut = 0.0f;
        if (progressOut > 1.0f) progressOut = 1.0f;

        float easeIn = 1.0f - (1.0f - progressIn) * (1.0f - progressIn);
        float easeOut = 1.0f - (1.0f - progressOut) * (1.0f - progressOut);
        float easeAlpha = easeIn * easeOut;
        float ease = easeAlpha;

        float targetY = 5 + activeIndex * (hitlogHeight + spacing);
        if (hitY[i] == -15.0f) {
            hitY[i] = targetY - 20.0f;
        }
        hitY[i] += (targetY - hitY[i]) * 0.15f;

        String p1 = "Hit ";
        String p2 = hitTarget[i];
        String p3 = " in the ";
        String p4 = hitHitbox[i];
        String p5 = " for ";
        String p6 = hitDamage[i];
        String p7 = " damage (" + hitHealth[i] + " health left)";

        int textWidth = (int) (render.getFontWidth(p1) + render.getFontWidth(p2) + render.getFontWidth(p3)
                            + render.getFontWidth(p4) + render.getFontWidth(p5)
                            + render.getFontWidth(p6) + render.getFontWidth(p7));

        int screenMargin = 5;
        int gradLen = 20;
        int finalX = screenMargin + gradLen;
        int startX = -textWidth - gradLen * 2;
        float currentX = startX + (finalX - startX) * easeOut;

        int x = (int) currentX;
        int y = (int) hitY[i];

        if (style == 2) { // OneTap style
            int bgAlpha = (int)(80 * ease);
            int lineAlpha = (int)(130 * ease);

            if (bgAlpha > 2) render.rect(x, y, x + textWidth, y + hitlogHeight, (bgAlpha << 24) | 0x111111);
            if (lineAlpha > 2) render.rect(x, y + hitlogHeight - 1, x + textWidth, y + hitlogHeight, (lineAlpha << 24) | 0x000000);

            for (int j = 0; j < gradLen; j++) {
                float ratioL = j / (float)(gradLen - 1);
                float fadeL = (float) Math.pow(ratioL, 1.5);
                int aBgL = (int) (bgAlpha * fadeL);
                int aLineL = (int) (lineAlpha * fadeL);

                int lx = x - gradLen + j;
                if (aBgL > 2) render.rect(lx, y, lx + 1, y + hitlogHeight, (aBgL << 24) | 0x111111);
                if (aLineL > 2) render.rect(lx, y + hitlogHeight - 1, lx + 1, y + hitlogHeight, (aLineL << 24) | 0x000000);

                float ratioR = 1.0f - (j / (float)(gradLen - 1));
                float fadeR = (float) Math.pow(ratioR, 1.5);
                int aBgR = (int) (bgAlpha * fadeR);
                int aLineR = (int) (lineAlpha * fadeR);

                int rx = x + textWidth + j;
                if (aBgR > 2) render.rect(rx, y, rx + 1, y + hitlogHeight, (aBgR << 24) | 0x111111);
                if (aLineR > 2) render.rect(rx, y + hitlogHeight - 1, rx + 1, y + hitlogHeight, (aLineR << 24) | 0x000000);
            }

            int totalWidth = textWidth + gradLen * 2;
            for (int j = 0; j < totalWidth; j++) {
                int color = getThemeColor(now - j * 15);
                int alpha = (int)(255 * ease);
                if (j < gradLen) {
                    float r = j / (float)(gradLen - 1);
                    alpha = (int)(alpha * Math.pow(r, 1.5));
                } else if (j >= totalWidth - gradLen) {
                    float r = 1.0f - (j - (totalWidth - gradLen)) / (float)(gradLen - 1);
                    alpha = (int)(alpha * Math.pow(r, 1.5));
                }
                if (alpha > 2) {
                    int cx = x - gradLen + j;
                    render.rect(cx, y, cx + 1, y + 1, (alpha << 24) | (color & 0xFFFFFF));
                }
            }
        } else { // Gamesense / other
            int bgAlpha = (int)(80 * ease);
            int lineAlpha = (int)(130 * ease);

            if (bgAlpha > 2) render.rect(x, y, x + textWidth, y + hitlogHeight, (bgAlpha << 24) | 0x000000);
            if (lineAlpha > 2) {
                render.rect(x, y, x + textWidth, y + 1, (lineAlpha << 24) | 0x000000);
                render.rect(x, y + hitlogHeight - 1, x + textWidth, y + hitlogHeight, (lineAlpha << 24) | 0x000000);
            }

            for (int j = 0; j < gradLen; j++) {
                float ratioL = j / (float)(gradLen - 1);
                float fadeL = (float) Math.pow(ratioL, 1.5);
                int aBgL = (int) ((25 + (80 - 25) * fadeL) * ease);
                int aLineL = (int) (130 * fadeL * ease);

                int lx = x - gradLen + j;
                if (aBgL > 2) render.rect(lx, y, lx + 1, y + hitlogHeight, (aBgL << 24) | 0x000000);
                if (aLineL > 2) {
                    render.rect(lx, y, lx + 1, y + 1, (aLineL << 24) | 0x000000);
                    render.rect(lx, y + hitlogHeight - 1, lx + 1, y + hitlogHeight, (aLineL << 24) | 0x000000);
                }

                float ratioR = 1.0f - (j / (float)(gradLen - 1));
                float fadeR = (float) Math.pow(ratioR, 1.5);
                int aBgR = (int) ((25 + (80 - 25) * fadeR) * ease);
                int aLineR = (int) (130 * fadeR * ease);

                int rx = x + textWidth + j;
                if (aBgR > 2) render.rect(rx, y, rx + 1, y + hitlogHeight, (aBgR << 24) | 0x000000);
                if (aLineR > 2) {
                    render.rect(rx, y, rx + 1, y + 1, (aLineR << 24) | 0x000000);
                    render.rect(rx, y + hitlogHeight - 1, rx + 1, y + hitlogHeight, (aLineR << 24) | 0x000000);
                }
            }
        }

        int textAlpha = (int)(255 * ease);
        if (textAlpha > 5) {
            int colorWhite = (textAlpha << 24) | 0xFFFFFF;
            int themeColor = getThemeColor(now);
            int colorAccent = (textAlpha << 24) | (themeColor & 0xFFFFFF);

            float drawX = x;
            float yPos = y + 4;

            render.text2d(p1, (int)drawX, (int)yPos, 1.0f, colorWhite, true);
            drawX += render.getFontWidth(p1);

            render.text2d(p2, (int)drawX, (int)yPos, 1.0f, colorAccent, true);
            drawX += render.getFontWidth(p2);

            render.text2d(p3, (int)drawX, (int)yPos, 1.0f, colorWhite, true);
            drawX += render.getFontWidth(p3);

            render.text2d(p4, (int)drawX, (int)yPos, 1.0f, colorAccent, true);
            drawX += render.getFontWidth(p4);

            render.text2d(p5, (int)drawX, (int)yPos, 1.0f, colorWhite, true);
            drawX += render.getFontWidth(p5);

            render.text2d(p6, (int)drawX, (int)yPos, 1.0f, colorAccent, true);
            drawX += render.getFontWidth(p6);

            render.text2d(p7, (int)drawX, (int)yPos, 1.0f, colorWhite, true);
        }

        activeIndex++;
    }
}

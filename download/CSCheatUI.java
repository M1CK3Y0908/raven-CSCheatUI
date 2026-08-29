// dev. M1CK3Y
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

String[] watermarkStyles = {"None", "gamesense", "OneTap"};
String[] animTimeOptions = {"\u00A7cDisabled", "\u00A7b0.1s", "\u00A7b0.2s", "\u00A7b0.3s", "\u00A7b0.4s", "\u00A7b0.5s", "\u00A7b0.6s", "\u00A7b0.7s", "\u00A7b0.8s", "\u00A7b0.9s", "\u00A7b1.0s"};
String[] fakeFpsOptions;
String[] themeOptions = {
    "Default","Gamesense","Rainbow","Aurora","Cherry","Cotton Candy",
    "Flare","Flower","Forest","Frost","Gold",
    "Grayscale","Inferno","Royal","Sandstorm","Sky","Vine"
};

void onLoad() {
    fakeFpsOptions = new String[50];
    fakeFpsOptions[0] = "\u00A7cDisabled";
    for (int i = 1; i <= 49; i++) {
        fakeFpsOptions[i] = "\u00A7a" + (i + 1) + "x";
    }

    modules.registerDescription("v1.0.1 dev. M1CK3Y");
    modules.registerSlider("Theme", "", 0, themeOptions);
    modules.registerSlider("Watermark Style", "", 0, watermarkStyles);
    modules.registerSlider("Animation Time", "", 0, animTimeOptions);
    modules.registerSlider("Fake FPS", "", 0, fakeFpsOptions);
    modules.registerSlider("Hitlog", "", 0, watermarkStyles);
}

void onEnable() {
    for (int i = 0; i < MAX_HITS; i++) hitActive[i] = false;
    frames = 0;
    currentFPS = 0;
    lastFPSUpdate = client.time();
}

int clampInt(int value, int min, int max) { 
    return value < min ? min : value > max ? max : value; 
}

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

void addPendingHit(Entity target, String name, String hitbox, float health) {
    long now = client.time();
    // If same entity already pending, just update hitbox and time (keep original health baseline)
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

void checkPendingHits() {
    long now = client.time();
    for (int i = 0; i < MAX_PENDING; i++) {
        Entity target = pendingEntity[i];
        if (target != null) {
            if (now - pendingTime[i] > 500) {
                // Timeout: force log to ensure it displays even if server doesn't update health
                addHit(pendingName[i], pendingHitbox[i], "2.0", String.format("%.1f", pendingHealth[i] - 2.0f));
                // Clear all pending entries for this entity
                for (int j = 0; j < MAX_PENDING; j++) {
                    if (pendingEntity[j] == target) pendingEntity[j] = null;
                }
                continue;
            }
            
            try {
                float currentHealth = target.getHealth();
                float damage = pendingHealth[i] - currentHealth;
                
                // If health dropped, the server registered the damage!
                if (damage > 0 && damage < 20.0f) { 
                    addHit(pendingName[i], pendingHitbox[i], String.format("%.1f", damage), String.format("%.1f", currentHealth));
                    // Clear all pending entries for this entity
                    for (int j = 0; j < MAX_PENDING; j++) {
                        if (pendingEntity[j] == target) pendingEntity[j] = null;
                    }
                } else if (damage < 0) {
                    // target healed (e.g. potion), reset baseline to new health
                    pendingHealth[i] = currentHealth;
                }
            } catch (Exception e) {
                // Wrapper doesn't support getHealth, force log immediately
                addHit(pendingName[i], pendingHitbox[i], "2.0", String.format("%.1f", pendingHealth[i] - 2.0f));
                // Clear all pending entries for this entity
                for (int j = 0; j < MAX_PENDING; j++) {
                    if (pendingEntity[j] == target) pendingEntity[j] = null;
                }
            }
        }
    }
}

boolean onPacketSent(CPacket packet) {
    int hitlogStyle = 0;
    try { hitlogStyle = (int) modules.getSlider(getScriptName(), "Hitlog"); } catch(Exception e){}
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
                    
                    // Add to pending queue, wait for server to subtract health
                    addPendingHit(target, tName, hitbox, tHealth);
                } else {
                    // Completely fallback if entity extraction fails to ensure hit displays
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
        currentFPS = frames;
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
                List players = world.getNetworkPlayers();
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
    try { localIdx = (int) modules.getSlider(getScriptName(), "Theme"); } catch(Exception e){}

    if (localIdx == 0) {
        int si = -1;
        try { si = (int) modules.getSlider("Settings", "Default theme"); } catch(Exception e){}
        if (si >= 0 && si < settingsThemeMap.length) {
            lo = settingsThemeMap[si].toLowerCase().trim();
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
        } catch(Exception e) { currentIP = "Singleplayer"; }
        
        try {
            currentTime = String.format("%tT", client.time());
        } catch(Exception e) {}
        
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
    
    int style = (int) modules.getSlider(getScriptName(), "Watermark Style");
    if (style == 1) drawGamesenseWatermark();
    else if (style == 2) drawOnetapWatermark();
    
    int hitlogStyle = (int) modules.getSlider(getScriptName(), "Hitlog");
    if (hitlogStyle > 0) {
        drawHitlog();
    }
}

void drawGamesenseWatermark() {
    long ms = client.time();
    int fontHeight = (int) render.getFontHeight();
    
    String t1a = "game";
    String t1b = "sense";
    
    int fakeFpsMult = (int) modules.getSlider(getScriptName(), "Fake FPS");
    int displayFPS = currentFPS;
    if (fakeFpsMult > 0) displayFPS *= (fakeFpsMult + 1);
    
    String t2 = String.valueOf(displayFPS);
    String t3 = "FPS";
    String t4 = String.valueOf(currentPing);
    String t5 = "PING";
    String t6 = currentIP;
    String t7 = String.format("%.0f", currentBPS * 100.0);
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
    
    int textWidth = w1a + w1b + gap + w2 + smallGap + w3 + gap + w4 + smallGap + w5 + gap + w6 + gap + w7 + smallGap + w8 + gap + w9;
    
    int padding = 5;
    int gradLen = 20;
    int screenMargin = 5;
    
    int animIdx = (int) modules.getSlider(getScriptName(), "Animation Time");
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
    
    // Background covers entire height
    render.rect(x, y, x + width, y + height, (bgAlpha << 24) | 0x000000);
    
    // Lines drawn ON TOP (inside background bounds)
    render.rect(x, y, x + width, y + 1, (lineAlpha << 24) | 0x000000);
    render.rect(x, y + height - 1, x + width, y + height, (lineAlpha << 24) | 0x000000);
    
    for (int i = 0; i < gradLen; i++) {
        float ratioL = i / (float)(gradLen - 1); 
        float fadeL = (float) Math.pow(ratioL, 1.5);
        int aBgL = (int) (25 + (bgAlpha - 25) * fadeL);
        int aLineL = (int) (lineAlpha * fadeL); // Fade line to 0 so it doesn't double up at the edge
        
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
    
    // Draw text segments by tracking their RIGHT edge to keep them static on the right when they expand
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
    
    render.text(t[0], (int)gsAnimX[0] - w[0], textY, 1.0f, 0xFFFFFFFF, true); // game
    render.text(t[1], (int)gsAnimX[1] - w[1], textY, 1.0f, themeColor, true); // sense
    render.text(t[2], (int)gsAnimX[2] - w[2], textY, 1.0f, themeColor, true); // FPS val
    render.text(t[3], (int)gsAnimX[3] - w[3], smallY, 0.5f, 0xFFFFFFFF, true); // FPS
    render.text(t[4], (int)gsAnimX[4] - w[4], textY, 1.0f, themeColor, true); // PING val
    render.text(t[5], (int)gsAnimX[5] - w[5], smallY, 0.5f, 0xFFFFFFFF, true); // PING
    render.text(t[6], (int)gsAnimX[6] - w[6], textY, 1.0f, 0xFFFFFFFF, true); // IP
    render.text(t[7], (int)gsAnimX[7] - w[7], textY, 1.0f, themeColor, true); // SPEED val
    render.text(t[8], (int)gsAnimX[8] - w[8], smallY, 0.5f, 0xFFFFFFFF, true); // SPEED
    render.text(t[9], (int)gsAnimX[9] - w[9], textY, 1.0f, 0xFFFFFFFF, true); // TIME
}

void drawOnetapWatermark() {
    long ms = client.time();
    int fontHeight = (int) render.getFontHeight();
    
    String o1 = "onetap | ";
    
    int fakeFpsMult = (int) modules.getSlider(getScriptName(), "Fake FPS");
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
    String o10 = String.format("%.0f", currentBPS * 100.0);
    String o11 = "SPEED";
    String o12 = " | ";
    String o13 = currentTime;
    String o14 = " | raven b4";
    
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
    
    int textWidth = w1 + w2 + smallGap + w3 + w4 + w5 + smallGap + w6 + w7 + w8 + w9 + w10 + smallGap + w11 + w12 + w13 + w14;
    
    int padding = 5;
    int gradLen = 20;
    int screenMargin = 5;
    
    int animIdx = (int) modules.getSlider(getScriptName(), "Animation Time");
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
    
    // Background covers entire height
    render.rect(x, y, x + width, y + height, (bgAlpha << 24) | 0x111111);
    
    // Bottom line ON TOP
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
    
    // Draw text segments by tracking their RIGHT edge to keep them static on the right when they expand
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
    
    render.text(t[0], (int)otAnimX[0] - w[0], textY, 1.0f, 0xFFFFFFFF, true);
    render.text(t[1], (int)otAnimX[1] - w[1], textY, 1.0f, themeColor, true);
    render.text(t[2], (int)otAnimX[2] - w[2], smallY, 0.5f, 0xFFFFFFFF, true);
    render.text(t[3], (int)otAnimX[3] - w[3], textY, 1.0f, 0xFFFFFFFF, true);
    render.text(t[4], (int)otAnimX[4] - w[4], textY, 1.0f, themeColor, true);
    render.text(t[5], (int)otAnimX[5] - w[5], smallY, 0.5f, 0xFFFFFFFF, true);
    render.text(t[6], (int)otAnimX[6] - w[6], textY, 1.0f, 0xFFFFFFFF, true);
    render.text(t[7], (int)otAnimX[7] - w[7], textY, 1.0f, 0xFFFFFFFF, true);
    render.text(t[8], (int)otAnimX[8] - w[8], textY, 1.0f, 0xFFFFFFFF, true);
    render.text(t[9], (int)otAnimX[9] - w[9], textY, 1.0f, themeColor, true);
    render.text(t[10], (int)otAnimX[10] - w[10], smallY, 0.5f, 0xFFFFFFFF, true);
    render.text(t[11], (int)otAnimX[11] - w[11], textY, 1.0f, 0xFFFFFFFF, true);
    render.text(t[12], (int)otAnimX[12] - w[12], textY, 1.0f, 0xFFFFFFFF, true);
    render.text(t[13], (int)otAnimX[13] - w[13], textY, 1.0f, 0xFFFFFFFF, true);
}

void drawHitlog() {
    long now = client.time();
    int fontHeight = (int) render.getFontHeight();
    int hitlogHeight = fontHeight + 6;
    int spacing = 5;
    
    int style = (int) modules.getSlider(getScriptName(), "Hitlog");
    int animIdx = (int) modules.getSlider(getScriptName(), "Animation Time");
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
        float ease = easeAlpha; // Keep variable for background rendering
        
        float targetY = 5 + activeIndex * (hitlogHeight + spacing);
        if (hitY[i] == -15.0f) {
            hitY[i] = targetY - 20.0f; // Start 20 pixels above for drop-down effect
        }
        hitY[i] += (targetY - hitY[i]) * 0.15f;
        
        String p1 = "Hit ";
        String p2 = hitTarget[i];
        String p3 = " in the ";
        String p4 = hitHitbox[i];
        String p5 = " for ";
        String p6 = hitDamage[i];
        String p7 = " damage (" + hitHealth[i] + " health left)";
        
        int textWidth = (int) (render.getFontWidth(p1) + render.getFontWidth(p2) + render.getFontWidth(p3) + render.getFontWidth(p4) + render.getFontWidth(p5) + render.getFontWidth(p6) + render.getFontWidth(p7));
        
        int screenMargin = 5;
        int gradLen = 20;
        int finalX = screenMargin + gradLen;
        int startX = -textWidth - gradLen * 2;
        // X only slides left on fade out (easeOut). Fade in stays at finalX.
        float currentX = startX + (finalX - startX) * easeOut;
        
        int x = (int) currentX;
        int y = (int) hitY[i];
        
        // Draw background matching the watermark style
        if (style == 2) { // Onetap style
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
        } else { // Gamesense style or None
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
        
        // Draw colored text on top of background
        int textAlpha = (int)(255 * ease);
        if (textAlpha > 5) {
            int colorWhite = (textAlpha << 24) | 0xFFFFFF;
            int themeColor = getThemeColor(now);
            int colorGreen = (textAlpha << 24) | (themeColor & 0xFFFFFF);
            
            float drawX = x;
            float yPos = y + 4;
            
            render.text(p1, (int)drawX, (int)yPos, 1.0f, colorWhite, true);
            drawX += render.getFontWidth(p1);
            
            render.text(p2, (int)drawX, (int)yPos, 1.0f, colorGreen, true);
            drawX += render.getFontWidth(p2);
            
            render.text(p3, (int)drawX, (int)yPos, 1.0f, colorWhite, true);
            drawX += render.getFontWidth(p3);
            
            render.text(p4, (int)drawX, (int)yPos, 1.0f, colorGreen, true);
            drawX += render.getFontWidth(p4);
            
            render.text(p5, (int)drawX, (int)yPos, 1.0f, colorWhite, true);
            drawX += render.getFontWidth(p5);
            
            render.text(p6, (int)drawX, (int)yPos, 1.0f, colorGreen, true);
            drawX += render.getFontWidth(p6);
            
            render.text(p7, (int)drawX, (int)yPos, 1.0f, colorWhite, true);
        }
        
        activeIndex++;
    }
}

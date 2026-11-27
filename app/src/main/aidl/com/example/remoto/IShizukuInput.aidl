package com.example.remoto;

interface IShizukuInput {
    boolean isUhidActive();
    String getStatusDetail();
    int getUhidBoundDisplayId();
    boolean bindUhidDisplay(int displayId, String displayUniqueId);
    boolean grantOverlayPermission(String packageName);
    boolean moveMouse(int dx, int dy);
    boolean clickMouse(int displayId, float x, float y, boolean right);
    boolean scrollMouse(int displayId, float x, float y, float vScroll);
    boolean dragMouse(int displayId, float x, float y, int action);
    boolean sendKey(int keyCode);
    void destroy();
}

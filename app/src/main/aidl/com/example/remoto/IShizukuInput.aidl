package com.example.remoto;

interface IShizukuInput {
    boolean isUhidActive();
    String getStatusDetail();
    boolean grantOverlayPermission(String packageName);
    boolean moveMouse(int dx, int dy);
    boolean clickMouse(int displayId, float x, float y, boolean right);
    boolean scrollMouse(int displayId, float x, float y, float vScroll);
    boolean dragMouse(int displayId, float x, float y, int action);
    boolean sendKey(int keyCode);
    boolean showOverlayCursor(int displayId, int x, int y);
    void hideOverlayCursor();
    void destroy();
}

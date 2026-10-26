package org.caremonitor.demo;

/** Preserve restored detail pages after the retired fourth module was removed. */
final class SensorModuleNavigation {
    static final int LAYOUT_VERSION=2;
    private SensorModuleNavigation() {}

    static boolean wasRemoved(int storedIndex,int layoutVersion) {
        return layoutVersion<LAYOUT_VERSION&&storedIndex==3;
    }

    static int restoredIndex(int storedIndex,int layoutVersion) {
        if(layoutVersion<LAYOUT_VERSION) {
            if(wasRemoved(storedIndex,layoutVersion))return 0;
            if(storedIndex>=4)storedIndex--;
        }
        return Math.max(0,Math.min(4,storedIndex));
    }
}

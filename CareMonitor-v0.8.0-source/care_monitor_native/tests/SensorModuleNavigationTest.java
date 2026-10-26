package org.caremonitor.demo;

/** An upgrade must not route an existing light detail page to the buzzer. */
public final class SensorModuleNavigationTest {
    private static int checks;
    private static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    public static void main(String[] arguments) {
        for(int index=0;index<3;index++)check(SensorModuleNavigation.restoredIndex(index,1)==index,"Original retained detail "+index);
        check(SensorModuleNavigation.restoredIndex(4,1)==3,"Original light detail follows light");
        check(SensorModuleNavigation.restoredIndex(5,1)==4,"Original buzzer detail follows buzzer");
        check(SensorModuleNavigation.wasRemoved(3,1),"Removed detail returns to modules overview");
        check(SensorModuleNavigation.restoredIndex(3,1)==0,"Removed index has safe fallback");
        for(int index=0;index<5;index++) {
            check(SensorModuleNavigation.restoredIndex(index,2)==index,"Current saved detail "+index);
            check(!SensorModuleNavigation.wasRemoved(index,2),"Current detail remains available "+index);
        }
        check(SensorModuleNavigation.restoredIndex(-1,1)==0,"Invalid negative legacy index");
        check(SensorModuleNavigation.restoredIndex(9,2)==4,"Invalid high current index");
        check(!SensorModuleNavigation.wasRemoved(4,1),"Legacy light is retained");
        System.out.println("SensorModuleNavigationTest: "+checks+" checks passed");
    }
}

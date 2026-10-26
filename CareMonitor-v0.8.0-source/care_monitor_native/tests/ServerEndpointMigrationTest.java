package org.caremonitor.demo;

/** Upgrade checks: the former team receiver moves without moving server-bound secrets. */
public final class ServerEndpointMigrationTest {
    private static int checks;
    private static void check(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
    private static void selected(String saved,String expected,boolean requiresNewCredential,String label){
        ServerEndpointMigration.Selection selected=ServerEndpointMigration.select(saved);
        check(expected.equals(selected.endpoint),label+" endpoint");
        check(requiresNewCredential==selected.requiresNewCredential,label+" credential boundary");
    }
    public static void main(String[] arguments){
        selected(null,RelayPolicy.DEFAULT_ENDPOINT,false,"New install");
        selected("",RelayPolicy.DEFAULT_ENDPOINT,false,"No saved server");
        selected(" \t",RelayPolicy.DEFAULT_ENDPOINT,false,"Blank saved server");
        selected(ServerEndpointMigration.PREVIOUS_DEFAULT,RelayPolicy.DEFAULT_ENDPOINT,true,"Former built-in receiver");
        selected(" HTTPS://10.238.118.117:8000/v1/../sensor-data ",RelayPolicy.DEFAULT_ENDPOINT,true,"Canonical former receiver");
        selected(RelayPolicy.DEFAULT_ENDPOINT,RelayPolicy.DEFAULT_ENDPOINT,false,"Already configured local computer");
        selected("https://my-server.example:8443/sensor-data","https://my-server.example:8443/sensor-data",false,"Custom server");
        selected("https://10.238.118.117:8000/v2/sensor-data","https://10.238.118.117:8000/v2/sensor-data",false,"Custom old host path");
        selected("https://10.238.118.117:8001/sensor-data","https://10.238.118.117:8001/sensor-data",false,"Custom old host port");
        selected("https://10.238.118.117.example:8000/sensor-data","https://10.238.118.117.example:8000/sensor-data",false,"Similar hostname");
        boolean rejected=false;try{ServerEndpointMigration.select("http://10.238.118.117:8000/sensor-data");}
        catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"Never downgrade transport during migration");
        System.out.println("ServerEndpointMigrationTest: "+checks+" checks passed");
    }
}

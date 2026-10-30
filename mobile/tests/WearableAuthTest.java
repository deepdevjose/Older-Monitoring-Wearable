package org.caremonitor.demo;

import java.util.HashSet;
import java.util.Set;

/** Independent fixed HMAC vectors plus phase, identity and replay boundaries. */
public final class WearableAuthTest {
    private static final String KEY="000102030405060708090a0b0c0d0e0f";
    private static final String CLIENT="00112233445566778899aabbccddeeff",SERVER="ffeeddccbbaa99887766554433221100";
    private static final String BOARD="12ea38a2547eb9e4852192c70b14dfc1a35411b42a30f3a3fc9ad5575c9cc5dc";
    private static final String PHONE="85e379302ca02129faa9b940e0abff72300813da05dc3f2397b539d929a54388";
    private static final String READY="64c99775ab4788d8e4b5ad4073bc7272d91053ba8954be5a70f54fd47b53c05d";
    private static int checks;
    private static WearableAuth fresh(){return new WearableAuth(WearableAuth.DEVICE_ID,KEY,CLIENT);}
    private static String challenge(){return "CM1 CHALLENGE elder-demo-01 "+SERVER+" "+BOARD;}
    public static void main(String[] ignored) {
        WearableAuth auth=fresh();check("not ready before connect",!auth.isReady());
        equal("hello fixed vector",auth.hello(),"CM1 HELLO "+CLIENT);
        equal("phone HMAC independent vector",auth.answer(challenge()),"CM1 AUTH "+PHONE);
        check("not ready after phone proof",!auth.isReady());
        check("board READY independent vector",auth.verifyReady("CM1 READY "+READY));
        check("ready only after mutual proof",auth.isReady());
        denied("second READY terminates session",()->auth.receive("CM1 READY "+READY));
        check("terminal after unexpected command",!auth.isReady());
        equal("formatted private key",WearableAuth.normalizeKey("0001-0203 0405-0607 0809-0A0B 0C0D-0E0F"),KEY);
        for(String key:new String[]{"","000000","g00102030405060708090a0b0c0d0e0f",KEY+"0",KEY.substring(1)})illegal("invalid key",()->new WearableAuth(key));
        illegal("null key",()->new WearableAuth(null));
        illegal("wrong linked identity",()->new WearableAuth("other",KEY));
        illegal("uppercase client nonce forbidden",()->new WearableAuth(WearableAuth.DEVICE_ID,KEY,CLIENT.toUpperCase()));
        String[] bad={
            "{}","CM1 READY "+READY,"CM1 CHALLENGE elder-other "+SERVER+" "+BOARD,
            "CM1 CHALLENGE elder-demo-01 "+SERVER+" "+PHONE,
            "CM1 CHALLENGE elder-demo-01 "+SERVER+" "+READY,
            "CM1 CHALLENGE elder-demo-01 "+SERVER.toUpperCase()+" "+BOARD,
            challenge()+" "," "+challenge(),challenge().replace("CHALLENGE ","CHALLENGE  "),
            challenge().replace("CM1","CM2"),challenge().replace(SERVER,SERVER.substring(1)),
            challenge().replace(BOARD,BOARD.substring(1)),challenge().replace(BOARD,BOARD.toUpperCase()),
            challenge().replace(BOARD,"g"+BOARD.substring(1)),challenge()+"\n",null
        };
        for(String line:bad){WearableAuth next=fresh();next.hello();denied("invalid board transcript",()->next.answer(line));
            denied("rejected session cannot recover",()->next.receive(challenge()));check("failed session not ready",!next.isReady());}
        WearableAuth missingHello=fresh();denied("proof before hello",()->missingHello.receive(challenge()));
        WearableAuth duplicateHello=fresh();duplicateHello.hello();denied("hello cannot repeat",duplicateHello::hello);
        denied("repeated hello terminates",()->duplicateHello.receive(challenge()));
        for(String proof:new String[]{BOARD,PHONE,"0"+READY.substring(1),READY.toUpperCase(),READY+" ",""}) {
            WearableAuth next=fresh();next.hello();next.answer(challenge());denied("wrong READY proof",()->next.verifyReady("CM1 READY "+proof));
            check("wrong READY cannot mark connected",!next.isReady());
        }
        WearableAuth wrongKey=new WearableAuth(WearableAuth.DEVICE_ID,"f00102030405060708090a0b0c0d0e0f",CLIENT);
        wrongKey.hello();denied("different board key",()->wrongKey.answer(challenge()));
        WearableAuth replay=new WearableAuth(WearableAuth.DEVICE_ID,KEY,"10112233445566778899aabbccddeeff");
        replay.hello();denied("old challenge cannot authenticate fresh phone nonce",()->replay.answer(challenge()));
        Set<String> nonces=new HashSet<>();
        for(int i=0;i<100;i++) {
            WearableAuth next=new WearableAuth(WearableAuth.DEVICE_ID,KEY);String hello=next.hello();
            check("fresh nonce shape",hello.matches("CM1 HELLO [0-9a-f]{32}"));check("fresh nonce uniqueness",nonces.add(hello));
            denied("fixed proof not valid for new nonce",()->next.answer(challenge()));
        }
        String scope=WearableAuth.scope("https://host/api/v1","elder1","AA:BB:CC:DD:EE:FF");
        check("scope cryptographic length",scope.matches("[0-9a-f]{64}"));
        equal("MAC case canonical",scope,WearableAuth.scope("https://host/api/v1","elder1","aa:bb:cc:dd:ee:ff"));
        check("different account isolated",!scope.equals(WearableAuth.scope("https://host/api/v1","elder2","AA:BB:CC:DD:EE:FF")));
        check("different server isolated",!scope.equals(WearableAuth.scope("https://other/api/v1","elder1","AA:BB:CC:DD:EE:FF")));
        check("different board isolated",!scope.equals(WearableAuth.scope("https://host/api/v1","elder1","AA:BB:CC:DD:EE:00")));
        illegal("invalid MAC cannot form scope",()->WearableAuth.scope("https://host/api/v1","elder1","../../bad"));
        System.out.println("PASS: "+checks+" WearableAuth checks");
    }
    private static void check(String label,boolean value){checks++;if(!value)throw new AssertionError(label);}
    private static void equal(String label,String actual,String expected){check(label,actual.equals(expected));}
    private static void denied(String label,Runnable operation){try{operation.run();throw new AssertionError(label);}catch(SecurityException expected){checks++;}}
    private static void illegal(String label,Runnable operation){try{operation.run();throw new AssertionError(label);}catch(IllegalArgumentException expected){checks++;}}
}

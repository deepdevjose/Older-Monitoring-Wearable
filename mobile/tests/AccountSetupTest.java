package org.caremonitor.demo;

/** Profile setup must require an explicit role and preserve input across back navigation. */
public final class AccountSetupTest {
    private static int checks;

    public static void main(String[] args) {
        AccountSetup empty = new AccountSetup();
        check("starts at role", empty.step == 0);
        check("role is required", !empty.problem().isEmpty() && !empty.next());
        check("no default adult account", !empty.elder() && empty.role.isEmpty());
        empty.back();
        check("back cannot pass first step", empty.step == 0);
        for (String role : new String[]{null, "", "adult", "ELDER", " elder", "caregiver "}) {
            empty.role = role;
            check("invalid roles cannot advance: " + role, !empty.next() && empty.step == 0);
            check("invalid roles are not adult", !empty.elder());
        }

        for (String role : new String[]{"elder", "caregiver"}) {
            AccountSetup setup = new AccountSetup();
            setup.role = role;
            check("explicit role accepted", setup.problem().isEmpty() && setup.next());
            check("roles keep their own meaning", setup.elder() == "elder".equals(role));
            for (String name : new String[]{null, "", " \t\n ", repeat('n', 101)}) {
                setup.name = name;
                check("invalid name blocks progress", !setup.problem().isEmpty() && !setup.next());
                check("invalid name stays at name step", setup.step == 1);
            }
            setup.name = " Ana ";
            check("trimmed one-character name accepted", nameProblem(setup, " a ").isEmpty());
            check("100-character name accepted", nameProblem(setup, repeat('n', 100)).isEmpty());
            setup.name = " Ana ";
            check("name advances to email", setup.next() && setup.step == 2);
            for (String email : new String[]{null, "", "person", "@example.com", "a@example",
                    "a@.com", "a@example.", "a@@example.com", "a b@example.com",
                    "a@ex ample.com", "a@example..com", "a@example.com\nbad",
                    "a@example.com.", "a@example.com/path", "a@-example.com", "a@example-.com"}) {
                setup.email = email;
                check("invalid email blocks progress", !setup.problem().isEmpty() && !setup.next());
                check("invalid email stays at email step", setup.step == 2);
            }
            setup.email = repeat('e', 242) + "@example.com";
            check("254-character email accepted", setup.email.length() == 254 && setup.problem().isEmpty());
            setup.email = "e" + setup.email;
            check("255-character email rejected", !setup.problem().isEmpty() && !setup.next());
            setup.email = "Ana+care@sub.example.com";
            check("plus-address and subdomain accepted", setup.problem().isEmpty());
            setup.email = " ana@example.com ";
            check("trimmed email accepted", setup.problem().isEmpty());
            check("email advances to final password step", setup.next() && setup.step == 3);
            check("password is outside profile validation", setup.problem().isEmpty());
            for (int attempt = 0; attempt < 5; attempt++) {
                check("final step cannot advance", !setup.next() && setup.step == 3);
            }
            setup.back();
            check("back returns to email", setup.step == 2);
            check("back preserves exact profile input", role.equals(setup.role)
                && " Ana ".equals(setup.name) && " ana@example.com ".equals(setup.email));
            check("navigation does not change account type", setup.elder() == "elder".equals(role));
            for (int attempt = 0; attempt < 5; attempt++) setup.back();
            check("repeated back stays within bounds", setup.step == 0);
            check("profile survives return to first step", role.equals(setup.role)
                && " Ana ".equals(setup.name) && " ana@example.com ".equals(setup.email));
            check("saved fields allow navigation forward again", setup.next() && setup.next()
                && setup.next() && setup.step == 3 && !setup.next());
            check("role was never silently replaced", role.equals(setup.role));
        }
        System.out.println("PASS: " + checks + " AccountSetup checks");
    }

    private static String nameProblem(AccountSetup setup, String name) {
        setup.name = name;
        return setup.problem();
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    private static void check(String label, boolean value) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
}

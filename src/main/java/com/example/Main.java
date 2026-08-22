package com.example;

import io.javalin.Javalin;
import org.mindrot.jbcrypt.BCrypt;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Main {

    // In-memory database for demonstration purposes
    private static final Map<String, Map<String, Object>> usersDatabase = new HashMap<>();

    private Main() {
        // Prevent instantiation
    }

    public static void main(String[] args) {

        // Create the default demo user
        seedDefaultUser();

        // Railway automatically provides the PORT environment variable
        int port = Integer.parseInt(
                System.getenv().getOrDefault("PORT", "7070")
        );

        // Create Javalin application
        Javalin app = Javalin.create(config -> {
            config.staticFiles.add("/static");
        });

        /*
         * ============================================================
         * FRONTEND ROUTES
         * ============================================================
         */

        app.get("/", ctx -> ctx.html(frontend()));

        app.get("/index.html", ctx -> ctx.html(frontend()));


        /*
         * ============================================================
         * SESSION CHECK
         * ============================================================
         */

        app.get("/api/me", ctx -> {

            String loggedInUser = ctx.sessionAttribute("currentUser");

            if (loggedInUser == null) {
                ctx.status(401).json(
                        Map.of("error", "Not authenticated")
                );
                return;
            }

            Map<String, Object> user = usersDatabase.get(loggedInUser);

            if (user == null) {
                ctx.status(404).json(
                        Map.of("error", "User session expired or invalid")
                );
                return;
            }

            ctx.json(
                    Map.of(
                            "username", loggedInUser,
                            "profile", publicProfile(user)
                    )
            );
        });


        /*
         * ============================================================
         * USER PROFILE AUTOFILL
         * ============================================================
         */

        app.get("/api/autofill-profile", ctx -> {

            String loggedInUser = ctx.sessionAttribute("currentUser");

            if (loggedInUser == null) {
                ctx.status(401).json(
                        Map.of("error", "Authentication required")
                );
                return;
            }

            Map<String, Object> user = usersDatabase.get(loggedInUser);

            if (user == null) {
                ctx.status(404).json(
                        Map.of("error", "User not found")
                );
                return;
            }

            ctx.json(publicProfile(user));
        });


        /*
         * ============================================================
         * SIGNUP
         * ============================================================
         */

        app.post("/api/signup", ctx -> {

            try {

                Map<String, Object> request = requestBody(ctx);

                String username = required(request, "username");

                synchronized (usersDatabase) {

                    if (usersDatabase.containsKey(username)) {

                        ctx.status(400).json(
                                Map.of("error", "Username already exists")
                        );

                        return;
                    }

                    usersDatabase.put(
                            username,
                            userRecord(request)
                    );
                }

                // Automatically log the user in
                ctx.sessionAttribute("currentUser", username);

                Map<String, Object> user = usersDatabase.get(username);

                ctx.status(201).json(
                        Map.of(
                                "message", "Account created successfully",
                                "username", username,
                                "profile", publicProfile(user)
                        )
                );

            } catch (IllegalArgumentException exception) {

                ctx.status(400).json(
                        Map.of("error", exception.getMessage())
                );
            }
        });


        /*
         * ============================================================
         * LOGIN
         * ============================================================
         */

        app.post("/api/login", ctx -> {

            try {

                Map<String, Object> request = requestBody(ctx);

                String username = required(request, "username");
                String password = required(request, "password");

                Map<String, Object> user =
                        usersDatabase.get(username);

                if (user == null) {

                    ctx.status(401).json(
                            Map.of(
                                    "error",
                                    "Invalid username or password"
                            )
                    );

                    return;
                }

                String hashedPassword =
                        (String) user.get("passwordHash");

                boolean passwordCorrect =
                        BCrypt.checkpw(password, hashedPassword);

                if (!passwordCorrect) {

                    ctx.status(401).json(
                            Map.of(
                                    "error",
                                    "Invalid username or password"
                            )
                    );

                    return;
                }

                // Create session
                ctx.sessionAttribute("currentUser", username);

                ctx.json(
                        Map.of(
                                "message", "Login successful",
                                "username", username,
                                "profile", publicProfile(user)
                        )
                );

            } catch (IllegalArgumentException exception) {

                ctx.status(400).json(
                        Map.of("error", exception.getMessage())
                );
            }
        });


        /*
         * ============================================================
         * LOGOUT
         * ============================================================
         */

        app.post("/api/logout", ctx -> {

            if (ctx.req().getSession(false) != null) {
                ctx.req().getSession().invalidate();
            }

            ctx.json(
                    Map.of(
                            "message",
                            "Logged out successfully"
                    )
            );
        });


        /*
         * ============================================================
         * EMERGENCY ACCOUNT FREEZE
         * ============================================================
         */

        app.post("/api/freeze-account", ctx -> {

            String loggedInUser =
                    ctx.sessionAttribute("currentUser");

            if (loggedInUser == null) {

                ctx.status(401).json(
                        Map.of(
                                "error",
                                "Authentication required"
                        )
                );

                return;
            }

            synchronized (usersDatabase) {

                Map<String, Object> user =
                        usersDatabase.get(loggedInUser);

                if (user == null) {

                    ctx.status(404).json(
                            Map.of(
                                    "error",
                                    "User not found"
                            )
                    );

                    return;
                }

                // Freeze account
                user.put("isFrozen", true);

                // Destroy active session
                if (ctx.req().getSession(false) != null) {
                    ctx.req().getSession().invalidate();
                }

                ctx.json(
                        Map.of(
                                "message",
                                "Account frozen successfully"
                        )
                );
            }
        });


        /*
         * ============================================================
         * GOVERNMENT / BANKING SCHEMES
         * ============================================================
         */

        app.get("/api/scheme/{schemeId}", ctx -> {

            String schemeId =
                    ctx.pathParam("schemeId");

            Map<String, Object> scheme =
                    schemes().get(schemeId);

            if (scheme == null) {

                ctx.status(404).json(
                        Map.of(
                                "error",
                                "Scheme not found"
                        )
                );

                return;
            }

            ctx.json(scheme);
        });


        /*
         * ============================================================
         * START SERVER
         * ============================================================
         */

        System.out.println(
                "AccessBank server starting on port " + port
        );

        app.start(port);
    }


    /*
     * ============================================================
     * DEFAULT USER
     * ============================================================
     */

    private static void seedDefaultUser() {

        if (!usersDatabase.isEmpty()) {
            return;
        }

        Map<String, Object> defaultUser =
                new LinkedHashMap<>();

        defaultUser.put(
                "passwordHash",
                BCrypt.hashpw(
                        "password123",
                        BCrypt.gensalt()
                )
        );

        defaultUser.putAll(profile());

        usersDatabase.put(
                "priya123",
                defaultUser
        );
    }


    /*
     * ============================================================
     * READ JSON REQUEST BODY
     * ============================================================
     */

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requestBody(
            io.javalin.http.Context ctx
    ) {

        try {

            Map<String, Object> body =
                    ctx.bodyAsClass(Map.class);

            return body != null
                    ? body
                    : Map.of();

        } catch (Exception exception) {

            throw new IllegalArgumentException(
                    "Request body must be valid JSON"
            );
        }
    }


    /*
     * ============================================================
     * REQUIRED FIELD VALIDATION
     * ============================================================
     */

    private static String required(
            Map<String, Object> request,
            String field
    ) {

        Object value = request.get(field);

        if (
                value == null ||
                value.toString().isBlank()
        ) {

            throw new IllegalArgumentException(
                    "Missing required field: " + field
            );
        }

        return value.toString().trim();
    }


    /*
     * ============================================================
     * CREATE USER RECORD
     * ============================================================
     */

    private static Map<String, Object> userRecord(
            Map<String, Object> request
    ) {

        Map<String, Object> user =
                new LinkedHashMap<>();

        String rawPassword =
                required(request, "password");

        // Store HASHED password, never raw password
        user.put(
                "passwordHash",
                BCrypt.hashpw(
                        rawPassword,
                        BCrypt.gensalt()
                )
        );

        user.put(
                "fullName",
                required(request, "fullName")
        );

        user.put(
                "dateOfBirth",
                required(request, "dateOfBirth")
        );

        user.put(
                "address",
                required(request, "address")
        );

        user.put(
                "phone",
                required(request, "phone")
        );

        user.put(
                "email",
                required(request, "email")
        );

        user.put(
                "governmentId",
                required(request, "governmentId")
        );

        user.put(
                "isFrozen",
                false
        );

        // Demo financial data
        user.put(
                "accountBalance",
                "₹0.00"
        );

        user.put(
                "creditScore",
                "Not available"
        );

        user.put(
                "transactionHistory",
                List.of("No transactions yet.")
        );

        return user;
    }


    /*
     * ============================================================
     * CREATE SAFE PUBLIC PROFILE
     * ============================================================
     */

    private static Map<String, Object> publicProfile(
            Map<String, Object> user
    ) {

        if (user == null) {
            return Map.of();
        }

        Map<String, Object> profile =
                new LinkedHashMap<>(user);

        // Never expose password hash
        profile.remove("passwordHash");

        return profile;
    }


    /*
     * ============================================================
     * LOAD FRONTEND HTML
     * ============================================================
     */

    private static String frontend() {

        try (
                var inputStream =
                        Main.class.getResourceAsStream(
                                "/static/index.html"
                        )
        ) {

            if (inputStream == null) {

                throw new IllegalStateException(
                        "Frontend file index.html was not found in "
                                + "src/main/resources/static/"
                );
            }

            return new String(
                    inputStream.readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8
            );

        } catch (Exception exception) {

            throw new IllegalStateException(
                    "Unable to read frontend file",
                    exception
            );
        }
    }


    /*
     * ============================================================
     * DEFAULT USER PROFILE
     * ============================================================
     */

    private static Map<String, Object> profile() {

        Map<String, Object> profile =
                new LinkedHashMap<>();

        profile.put(
                "fullName",
                "Priya Sharma"
        );

        profile.put(
                "dateOfBirth",
                "1990-01-15"
        );

        profile.put(
                "address",
                "42 Green Park Road, New Delhi, India"
        );

        profile.put(
                "phone",
                "+91-98765-43210"
        );

        profile.put(
                "email",
                "priya.sharma@example.com"
        );

        profile.put(
                "governmentId",
                "GOV-ID-PRIYA-1990"
        );

        profile.put(
                "isFrozen",
                false
        );

        profile.put(
                "accountBalance",
                "₹1,24,500.00"
        );

        profile.put(
                "creditScore",
                "780 (Excellent)"
        );

        profile.put(
                "transactionHistory",
                List.of(
                        "₹5,000 received - Salary credit",
                        "₹1,250 paid - Utility bill",
                        "₹2,000 paid - Online purchase",
                        "₹15,000 received - Bank transfer"
                )
        );

        return profile;
    }


    /*
     * ============================================================
     * SCHEME DATABASE
     * ============================================================
     */

    private static Map<String, Map<String, Object>> schemes() {

        Map<String, Map<String, Object>> schemes =
                new LinkedHashMap<>();


        schemes.put(
                "senior-citizen",
                scheme(
                        "Senior Citizen Benefits Scheme",
                        List.of(
                                "Government ID",
                                "Proof of age",
                                "Address proof",
                                "Recent photograph"
                        ),
                        "Submit your identity, age, and address documents "
                                + "through the nearest authorized service center.",
                        ""
                )
        );


        schemes.put(
                "student-loan",
                scheme(
                        "Student Loan Assistance Scheme",
                        List.of(
                                "Government ID",
                                "Admission letter",
                                "Academic transcripts",
                                "Income certificate"
                        ),
                        "Complete the student loan application with your "
                                + "admission and financial documents, then "
                                + "submit it to a participating bank.",
                        ""
                )
        );


        schemes.put(
                "jan-dhan",
                scheme(
                        "Pradhan Mantri Jan Dhan Yojana (PMJDY)",
                        List.of(
                                "Aadhaar Card",
                                "Voter ID",
                                "PAN Card",
                                "Passport size photo"
                        ),
                        "Visit any bank branch or Bank Mitra outlet with "
                                + "your Aadhaar card to open a bank account.",
                        ""
                )
        );


        schemes.put(
                "sukanya-samriddhi",
                scheme(
                        "Sukanya Samriddhi Yojana (SSY)",
                        List.of(
                                "Birth Certificate of girl child",
                                "Parent or Guardian ID proof",
                                "Address proof",
                                "Photo"
                        ),
                        "Fill out the SSY account opening form at your "
                                + "nearest post office or authorized bank "
                                + "with the required guardian details.",
                        ""
                )
        );


        schemes.put(
                "kisan-credit",
                scheme(
                        "Kisan Credit Card (KCC) Scheme",
                        List.of(
                                "Land ownership documents",
                                "Identity proof",
                                "Address proof",
                                "Crop details"
                        ),
                        "Submit land records and agricultural operation "
                                + "details at an eligible bank branch.",
                        ""
                )
        );


        schemes.put(
                "atal-pension",
                scheme(
                        "Atal Pension Yojana (APY)",
                        List.of(
                                "Aadhaar Card",
                                "Active savings bank account",
                                "Mobile number"
                        ),
                        "Link your savings account and complete the required "
                                + "authorization process through your bank.",
                        ""
                )
        );


        schemes.put(
                "home-loan",
                scheme(
                        "Pradhan Mantri Awas Yojana (PMAY) Home Loan",
                        List.of(
                                "Income proof",
                                "Property valuation report",
                                "Aadhaar Card",
                                "Bank statements"
                        ),
                        "Apply through an approved housing finance provider "
                                + "with the required property and income "
                                + "documents.",
                        ""
                )
        );


        schemes.put(
                "mudra-loan",
                scheme(
                        "Pradhan Mantri MUDRA Yojana (PMMY)",
                        List.of(
                                "Business registration certificate",
                                "Identity proof",
                                "Bank statement",
                                "Business plan outline"
                        ),
                        "Submit your business proposal and required identity "
                                + "records to an eligible lending institution.",
                        ""
                )
        );


        schemes.put(
                "fixed-deposit",
                scheme(
                        "Tax Saver Fixed Deposit Scheme",
                        List.of(
                                "PAN Card",
                                "Aadhaar Card",
                                "Savings account details"
                        ),
                        "Deposit funds for the required term through your "
                                + "banking service or by submitting an FD "
                                + "request at the branch.",
                        ""
                )
        );


        schemes.put(
                "nsc",
                scheme(
                        "National Savings Certificate (NSC)",
                        List.of(
                                "Identity proof",
                                "Address proof",
                                "PAN Card",
                                "Purchase form"
                        ),
                        "Purchase an NSC through an authorized postal or "
                                + "official online service.",
                        ""
                )
        );


        return schemes;
    }


    /*
     * ============================================================
     * CREATE SCHEME OBJECT
     * ============================================================
     */

    private static Map<String, Object> scheme(
            String name,
            List<String> requiredDocuments,
            String instructions,
            String videoUrl
    ) {

        Map<String, Object> scheme =
                new LinkedHashMap<>();

        scheme.put("name", name);

        scheme.put("schemeName", name);

        scheme.put(
                "requiredDocuments",
                requiredDocuments
        );

        scheme.put(
                "instructions",
                instructions
        );

        scheme.put(
                "videoUrl",
                videoUrl
        );

        return scheme;
    }
}

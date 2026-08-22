package com.example;

import io.javalin.Javalin;
import org.mindrot.jbcrypt.BCrypt;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Main {

    private static final Map<String, Map<String, Object>> usersDatabase =
            new HashMap<>();

    private Main() {
    }

    public static void main(String[] args) {

        seedDefaultUser();

        // Railway provides PORT automatically.
        // Local fallback is 7070.
        int port = Integer.parseInt(
                System.getenv().getOrDefault("PORT", "7070")
        );

        Javalin app = Javalin.create(config -> {
            config.staticFiles.add("/static");
        });

        // Explicitly serve the frontend at /
        app.get("/", ctx -> ctx.redirect("/index.html"));

        // ============================
        // ACTIVE SESSION CHECK
        // ============================
        app.get("/api/me", ctx -> {

            String loggedInUser =
                    ctx.sessionAttribute("currentUser");

            if (loggedInUser == null) {
                ctx.status(401).json(
                        Map.of("error", "Not authenticated")
                );
                return;
            }

            Map<String, Object> user =
                    usersDatabase.get(loggedInUser);

            if (user == null) {
                ctx.status(404).json(
                        Map.of("error",
                                "User session expired or invalid")
                );
                return;
            }

            ctx.json(Map.of(
                    "username", loggedInUser,
                    "profile", publicProfile(user)
            ));
        });

        // ============================
        // PROFILE AUTOFILL
        // ============================
        app.get("/api/autofill-profile", ctx -> {

            String loggedInUser =
                    ctx.sessionAttribute("currentUser");

            if (loggedInUser == null) {
                ctx.status(401).json(
                        Map.of("error", "Authentication required")
                );
                return;
            }

            Map<String, Object> user =
                    usersDatabase.get(loggedInUser);

            if (user == null) {
                ctx.status(404).json(
                        Map.of("error", "User not found")
                );
                return;
            }

            ctx.json(publicProfile(user));
        });

        // ============================
        // SIGNUP
        // ============================
        app.post("/api/signup", ctx -> {

            try {
                Map<String, Object> request =
                        requestBody(ctx);

                String username =
                        required(request, "username");

                synchronized (usersDatabase) {

                    if (usersDatabase.containsKey(username)) {
                        ctx.status(400).json(
                                Map.of("error",
                                        "Username already exists")
                        );
                        return;
                    }

                    usersDatabase.put(
                            username,
                            userRecord(request)
                    );
                }

                // Create login session
                ctx.sessionAttribute(
                        "currentUser",
                        username
                );

                ctx.status(201).json(Map.of(
                        "message",
                        "Account created successfully",

                        "username",
                        username,

                        "profile",
                        publicProfile(
                                usersDatabase.get(username)
                        )
                ));

            } catch (IllegalArgumentException exception) {

                ctx.status(400).json(
                        Map.of(
                                "error",
                                exception.getMessage()
                        )
                );
            }
        });

        // ============================
        // LOGIN
        // ============================
        app.post("/api/login", ctx -> {

            try {

                Map<String, Object> request =
                        requestBody(ctx);

                String username =
                        required(request, "username");

                String password =
                        required(request, "password");

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

                if (!BCrypt.checkpw(
                        password,
                        hashedPassword
                )) {

                    ctx.status(401).json(
                            Map.of(
                                    "error",
                                    "Invalid username or password"
                            )
                    );
                    return;
                }

                ctx.sessionAttribute(
                        "currentUser",
                        username
                );

                ctx.json(Map.of(
                        "message",
                        "Login successful",

                        "username",
                        username,

                        "profile",
                        publicProfile(user)
                ));

            } catch (IllegalArgumentException exception) {

                ctx.status(400).json(
                        Map.of(
                                "error",
                                exception.getMessage()
                        )
                );
            }
        });

        // ============================
        // LOGOUT
        // ============================
        app.post("/api/logout", ctx -> {

            var session =
                    ctx.req().getSession(false);

            if (session != null) {
                session.invalidate();
            }

            ctx.json(
                    Map.of(
                            "message",
                            "Logged out successfully"
                    )
            );
        });

        // ============================
        // EMERGENCY ACCOUNT FREEZE
        // ============================
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

                user.put("isFrozen", true);

                var session =
                        ctx.req().getSession(false);

                if (session != null) {
                    session.invalidate();
                }

                ctx.json(
                        Map.of(
                                "message",
                                "Account frozen successfully"
                        )
                );
            }
        });

        // ============================
        // GOVERNMENT SCHEMES
        // ============================
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

        // ============================
        // START SERVER
        // ============================
        app.start(port);
    }

    // ========================================
    // DEFAULT USER
    // ========================================
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

    // ========================================
    // REQUEST BODY
    // ========================================
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

    // ========================================
    // REQUIRED FIELD
    // ========================================
    private static String required(
            Map<String, Object> request,
            String field
    ) {

        Object value =
                request.get(field);

        if (value == null ||
                value.toString().isBlank()) {

            throw new IllegalArgumentException(
                    "Missing required field: "
                            + field
            );
        }

        return value.toString().trim();
    }

    // ========================================
    // CREATE USER RECORD
    // ========================================
    private static Map<String, Object> userRecord(
            Map<String, Object> request
    ) {

        Map<String, Object> user =
                new LinkedHashMap<>();

        String rawPassword =
                required(request, "password");

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

        user.put("isFrozen", false);

        user.put("accountBalance", "₹0.00");

        user.put(
                "creditScore",
                "N/A - New Account"
        );

        user.put(
                "transactionHistory",
                List.of(
                        "Account created successfully."
                )
        );

        return user;
    }

    // ========================================
    // REMOVE SENSITIVE DATA
    // ========================================
    private static Map<String, Object> publicProfile(
            Map<String, Object> user
    ) {

        if (user == null) {
            return Map.of();
        }

        Map<String, Object> profile =
                new LinkedHashMap<>(user);

        profile.remove("passwordHash");

        return profile;
    }

    // ========================================
    // DEFAULT PROFILE
    // ========================================
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
                        "Salary credited: ₹50,000",
                        "Electricity bill payment: ₹2,450",
                        "UPI payment completed: ₹850"
                )
        );

        return profile;
    }

    // ========================================
    // SCHEMES
    // ========================================
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
                        "Submit your identity, age, and address documents through the nearest authorized service center.",
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
                        "Complete the student loan application with your admission and financial documents, then submit it to a participating bank.",
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
                        "Visit any bank branch or Bank Mitra outlet to begin the account opening process.",
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
                        "Fill out the SSY account opening form at an authorized post office or bank.",
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
                        "Submit the required agricultural and identity documents at an authorized bank.",
                        ""
                )
        );

        schemes.put(
                "atal-pension",
                scheme(
                        "Atal Pension Yojana (APY)",
                        List.of(
                                "Aadhaar Card",
                                "Active savings account",
                                "Mobile number"
                        ),
                        "Complete the required enrollment process through your bank.",
                        ""
                )
        );

        schemes.put(
                "home-loan",
                scheme(
                        "Pradhan Mantri Awas Yojana (PMAY) Home Loan",
                        List.of(
                                "Income proof",
                                "Property documents",
                                "Aadhaar Card",
                                "Bank statements"
                        ),
                        "Apply through an approved housing finance institution with the required documents.",
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
                                "Business plan"
                        ),
                        "Submit the required business and identity documents to a participating financial institution.",
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
                        "Contact your bank for the applicable fixed deposit application process.",
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
                        "Contact an authorized post office or official service provider for the purchase process.",
                        ""
                )
        );

        return schemes;
    }

    // ========================================
    // CREATE SCHEME OBJECT
    // ========================================
    private static Map<String, Object> scheme(
            String name,
            List<String> requiredDocuments,
            String instructions,

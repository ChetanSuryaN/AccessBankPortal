package com.example;

import io.javalin.Javalin;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Main {
    private static final Map<String, Map<String, Object>> usersDatabase = new HashMap<>();

    private Main() {
    }

    public static void main(String[] args) {
        seedDefaultUser();

        // 1. Read Railway's dynamic PORT environment variable. Fallback to 7070 if local.
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "7070"));

        // 2. Configure Javalin to automatically host static files from resource folder
        Javalin app = Javalin.create(config -> {
            config.staticFiles.add("/static"); 
        });

        // 3. Home routes
        app.get("/", ctx -> ctx.html(frontend()));
        app.get("/index.html", ctx -> ctx.html(frontend()));
        
        app.get("/api/autofill-profile", ctx -> ctx.json(publicProfile(usersDatabase.get("priya123"))));
        
        app.post("/api/signup", ctx -> {
            try {
                Map<String, Object> request = requestBody(ctx);
                String username = required(request, "username");

                synchronized (usersDatabase) {
                    if (usersDatabase.containsKey(username)) {
                        ctx.status(400).json(Map.of("error", "Username already exists"));
                        return;
                    }
                    usersDatabase.put(username, userRecord(request));
                }
                ctx.status(201).json(Map.of(
                        "message", "Account created successfully",
                        "username", username,
                        "profile", publicProfile(usersDatabase.get(username))
                ));
            } catch (IllegalArgumentException exception) {
                ctx.status(400).json(Map.of("error", exception.getMessage()));
            }
        });

        app.post("/api/login", ctx -> {
            try {
                Map<String, Object> request = requestBody(ctx);
                String username = required(request, "username");
                String password = required(request, "password");
                Map<String, Object> user = usersDatabase.get(username);

                if (user == null || !password.equals(user.get("password"))) {
                    ctx.status(401).json(Map.of("error", "Invalid username or password"));
                    return;
                }

                ctx.json(Map.of(
                        "message", "Login successful",
                        "username", username,
                        "profile", publicProfile(user)
                ));
            } catch (IllegalArgumentException exception) {
                ctx.status(400).json(Map.of("error", exception.getMessage()));
            }
        });

        app.post("/api/freeze-account", ctx -> {
            try {
                Map<String, Object> request = requestBody(ctx);
                String username = required(request, "username");
                synchronized (usersDatabase) {
                    Map<String, Object> user = usersDatabase.get(username);
                    if (user != null) {
                        user.put("isFrozen", true);
                        ctx.json(Map.of("message", "Account frozen successfully"));
                    } else {
                        ctx.status(404).json(Map.of("error", "User not found"));
                    }
                }
            } catch (Exception exception) {
                ctx.status(400).json(Map.of("error", exception.getMessage()));
            }
        });

        app.get("/api/scheme/{schemeId}", ctx -> {
            String schemeId = ctx.pathParam("schemeId");
            Map<String, Object> scheme = schemes().get(schemeId);

            if (scheme == null) {
                ctx.status(404).json(Map.of("error", "Scheme not found"));
                return;
            }

            ctx.json(scheme);
        });

        // Start on the dynamic cloud port!
        app.start(port);
    }

    static String greeting() {
        return "Hello, Maven!";
    }

    private static void seedDefaultUser() {
        if (!usersDatabase.isEmpty()) {
            return;
        }
        Map<String, Object> defaultUser = new LinkedHashMap<>();
        defaultUser.put("password", "password123");
        defaultUser.putAll(profile());
        usersDatabase.put("priya123", defaultUser);
    }

    private static Map<String, Object> requestBody(io.javalin.http.Context ctx) {
        try {
            return ctx.bodyAsClass(Map.class);
        } catch (Exception exception) {
            ctx.status(400).json(Map.of("error", "Request body must be valid JSON"));
            return Map.of();
        }
    }

    private static String required(Map<String, Object> request, String field) {
        Object value = request.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + field);
        }
        return value.toString();
    }

    private static Map<String, Object> userRecord(Map<String, Object> request) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("password", required(request, "password"));
        user.put("fullName", required(request, "fullName"));
        user.put("dateOfBirth", required(request, "dateOfBirth"));
        user.put("address", required(request, "address"));
        user.put("phone", required(request, "phone"));
        user.put("email", required(request, "email"));
        user.put("governmentId", required(request, "governmentId"));
        user.put("isFrozen", false);
        return user;
    }

    private static Map<String, Object> publicProfile(Map<String, Object> user) {
        if (user == null) {
            return Map.of();
        }
        Map<String, Object> profile = new LinkedHashMap<>(user);
        profile.remove("password");
        return profile;
    }

    private static String frontend() {
        try (var inputStream = Main.class.getResourceAsStream("/static/index.html")) {
            if (inputStream == null) {
                throw new IllegalStateException("Frontend file index.html was not found in resources/static/");
            }
            return new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to read frontend file from resources path", exception);
        }
    }

    private static Map<String, Object> profile() {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("fullName", "Priya Sharma");
        profile.put("dateOfBirth", "1990-01-15");
        profile.put("address", "42 Green Park Road, New Delhi, India");
        profile.put("phone", "+91-98765-43210");
        profile.put("email", "priya.sharma@example.com");
        profile.put("governmentId", "GOV-ID-PRIYA-1990");
        profile.put("isFrozen", false);
        return profile;
    }

    private static Map<String, Map<String, Object>> schemes() {
        Map<String, Map<String, Object>> schemes = new LinkedHashMap<>();

        schemes.put("senior-citizen", scheme(
                "Senior Citizen Benefits Scheme",
                List.of("Government ID", "Proof of age", "Address proof", "Recent photograph"),
                "Submit your identity, age, and address documents through the nearest authorized service center.",
                "https://example.com"
        ));

        schemes.put("student-loan", scheme(
                "Student Loan Assistance Scheme",
                List.of("Government ID", "Admission letter", "Academic transcripts", "Income certificate"),
                "Complete the student loan application with your admission and financial documents, then submit it to a participating bank.",
                "https://example.com"
        ));

        schemes.put("jan-dhan", scheme(
                "Pradhan Mantri Jan Dhan Yojana (PMJDY)",
                List.of("Aadhaar Card", "Voter ID", "PAN Card", "Passport size photo"),
                "Visit any bank branch or Bank Mitra outlet with your Aadhaar card to open a zero-balance account instantly.",
                "https://example.com"
        ));

        schemes.put("sukanya-samriddhi", scheme(
                "Sukanya Samriddhi Yojana (SSY)",
                List.of("Birth Certificate of girl child", "Parent/Guardian ID proof", "Address proof", "Photo"),
                "Fill out the SSY account opening form at your nearest post office or authorized commercial bank with guardian details.",
                "https://example.com"
        ));

        schemes.put("kisan-credit", scheme(
                "Kisan Credit Card (KCC) Scheme",
                List.of("Land Ownership Documents", "Identity Proof", "Address Proof", "Crop details"),
                "Submit land registry records and agricultural operation details at your local rural bank branch.",
                "https://example.com"
        ));

        schemes.put("atal-pension", scheme(
                "Atal Pension Yojana (APY)",
                List.of("Aadhaar Card", "Active Savings Bank Account", "Mobile number"),
                "Link your savings account and complete the auto-debit authorization form at your home bank branch.",
                "https://example.com"
        ));

        schemes.put("home-loan", scheme(
                "Pradhan Mantri Awas Yojana (PMAY) Home Loan",
                List.of("Income Proof", "Property valuation report", "Aadhaar Card", "Bank statements (6 months)"),
                "Apply online or visit an approved housing finance lender with property documents and salary/income statements.",
                "https://example.com"
        ));

        schemes.put("mudra-loan", scheme(
                "Pradhan Mantri MUDRA Yojana (PMMY)",
                List.of("Business Registration certificate", "Identity Proof", "Bank statement", "Business plan outline"),
                "Submit your business proposal and identity records to any commercial bank, RRB, or MFI offering Mudra loans.",
                "https://example.com"
        ));

        schemes.put("fixed-deposit", scheme(
                "Tax Saver Fixed Deposit Scheme",
                List.of("PAN Card", "Aadhaar Card", "Existing Savings account details"),
                "Deposit funds for a locked term of 5 years through net banking or by filling an FD request form at the branch.",
                "https://example.com"
        ));

        schemes.put("nsc", scheme(
                "National Savings Certificate (NSC)",
                List.of("Identity Proof", "Address Proof", "PAN Card", "Purchase form"),
                "Purchase NSC certificates directly through your local post office branch or authorized online portal.",
                "https://example.com"
        ));

        return schemes;
    }

    private static Map<String, Object> scheme(
            String name,
            List<String> requiredDocuments,
            String instructions,
            String videoUrl
    ) {
        Map<String, Object> scheme = new LinkedHashMap<>();
        scheme.put("name", name);
        scheme.put("schemeName", name);
        scheme.put("requiredDocuments", requiredDocuments);
        scheme.put("instructions", instructions);
        scheme.put("videoUrl", videoUrl);
        return scheme;
    }
}

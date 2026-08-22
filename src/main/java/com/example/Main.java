package com.example;

import io.javalin.Javalin;
import io.javalin.http.Context;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Main {
    private static final Map<String, Map<String, Object>> usersDatabase = new HashMap<>();
    private static final String SESSION_USER_KEY = "currentUser";

    private Main() {
    }

    public static void main(String[] args) {
        seedDefaultUser();

        // Support dynamic port binding (e.g. Railway, Heroku) or default to 7070
        int port = 7070;
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.isBlank()) {
            port = Integer.parseInt(envPort);
        }

        Javalin app = Javalin.create();

        // Public Web Pages
        app.get("/", ctx -> ctx.html(frontend()));
        app.get("/index.html", ctx -> ctx.html(frontend()));

        // Public API Endpoint
        app.get("/api/scheme/{schemeId}", ctx -> {
            String schemeId = ctx.pathParam("schemeId");
            Map<String, Object> scheme = schemes().get(schemeId);

            if (scheme == null) {
                ctx.status(404).json(Map.of("error", "Scheme not found"));
                return;
            }

            ctx.json(scheme);
        });

        // Public Authentication Endpoints
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

                // Automatically log user in upon registration
                ctx.sessionAttribute(SESSION_USER_KEY, username);

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

                // Secure active session
                ctx.sessionAttribute(SESSION_USER_KEY, username);

                ctx.json(Map.of(
                        "message", "Login successful",
                        "username", username,
                        "profile", publicProfile(user)
                ));
            } catch (IllegalArgumentException exception) {
                ctx.status(400).json(Map.of("error", exception.getMessage()));
            }
        });

        app.post("/api/logout", ctx -> {
            ctx.req().getSession().invalidate();
            ctx.json(Map.of("message", "Logged out successfully"));
        });

        // Protect specific endpoint patterns
        app.before("/api/protected/*", Main::requireAuthentication);

        // Protected Endpoints
        app.get("/api/protected/profile", ctx -> {
            String loggedInUser = ctx.sessionAttribute(SESSION_USER_KEY);
            Map<String, Object> user = usersDatabase.get(loggedInUser);
            if (user == null) {
                ctx.status(404).json(Map.of("error", "User not found"));
                return;
            }
            ctx.json(publicProfile(user));
        });

        app.get("/api/protected/dashboard-metrics", ctx -> {
            String loggedInUser = ctx.sessionAttribute(SESSION_USER_KEY);
            Map<String, Object> user = usersDatabase.get(loggedInUser);

            Map<String, Object> response = new HashMap<>();
            response.put("balance", "₹2,45,800.00");
            response.put("cibil", "785 (Excellent)");
            response.put("profile", publicProfile(user));
            response.put("history", List.of(
                    "Deposit: +₹10,000.00 (Salary Credit)",
                    "Transfer: -₹1,200.00 (Utility Bill)",
                    "Interest Credited: +₹450.00"
            ));

            ctx.json(response);
        });

        app.start(port);
    }

    private static void requireAuthentication(Context ctx) {
        String loggedInUser = ctx.sessionAttribute(SESSION_USER_KEY);
        if (loggedInUser == null) {
            ctx.status(401).json(Map.of("error", "Unauthorized: Please log in to access this resource."));
        }
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

    private static Map<String, Object> requestBody(Context ctx) {
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
        Path[] candidates = {
                Path.of("index.html"),
                Path.of("../index.html"),
                Path.of("../../index.html")
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.readString(candidate);
                } catch (java.io.IOException exception) {
                    throw new IllegalStateException("Unable to read frontend file: " + candidate, exception);
                }
            }
        }
        throw new IllegalStateException("Frontend file index.html was not found.");
    }

    private static Map<String, Object> profile() {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("fullName", "Priya Sharma");
        profile.put("dateOfBirth", "1990-01-15");
        profile.put("address", "42 Green Park Road, New Delhi, India");
        profile.put("phone", "+91-98765-43210");
        profile.put("email", "priya.sharma@example.com");
        profile.put("governmentId", "GOV-ID-PRIYA-1990");
        return profile;
    }

    private static Map<String, Map<String, Object>> schemes() {
        Map<String, Map<String, Object>> schemes = new LinkedHashMap<>();

        schemes.put("senior-citizen", scheme(
                "Senior Citizen Benefits Scheme",
                List.of("Government ID", "Proof of age", "Address proof", "Recent photograph"),
                "Submit your identity, age, and address documents through the nearest authorized service center.",
                "https://example.com/videos/senior-citizen-application"
        ));

        schemes.put("student-loan", scheme(
                "Student Loan Assistance Scheme",
                List.of("Government ID", "Admission letter", "Academic transcripts", "Income certificate"),
                "Complete the student loan application with your admission and financial documents, then submit it to a participating bank.",
                "https://example.com/videos/student-loan-application"
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
        scheme.put("requiredDocuments", requiredDocuments);
        scheme.put("instructions", instructions);
        scheme.put("videoUrl", videoUrl);
        return scheme;
    }
}

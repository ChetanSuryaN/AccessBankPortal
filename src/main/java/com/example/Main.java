package com.example;

import io.javalin.Javalin;
import org.mindrot.jbcrypt.BCrypt;

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

        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "7070"));

        Javalin app = Javalin.create(config -> {
            config.staticFiles.add("/static");
        });

        app.get("/", ctx -> ctx.html(frontend()));
        app.get("/index.html", ctx -> ctx.html(frontend()));

        // Check active session status
        app.get("/api/me", ctx -> {
            String loggedInUser = ctx.sessionAttribute("currentUser");
            if (loggedInUser == null) {
                ctx.status(401).json(Map.of("error", "Not authenticated"));
                return;
            }
            Map<String, Object> user = usersDatabase.get(loggedInUser);
            if (user == null) {
                ctx.status(404).json(Map.of("error", "User session expired or invalid"));
                return;
            }
            ctx.json(Map.of("username", loggedInUser, "profile", publicProfile(user)));
        });

        // Protected profile endpoint
        app.get("/api/autofill-profile", ctx -> {
            String loggedInUser = ctx.sessionAttribute("currentUser");
            if (loggedInUser == null) {
                ctx.status(401).json(Map.of("error", "Authentication required"));
                return;
            }
            ctx.json(publicProfile(usersDatabase.get(loggedInUser)));
        });

        // Registration endpoint with BCrypt password hashing
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

                // Authenticate session after successful signup
                ctx.sessionAttribute("currentUser", username);

                ctx.status(201).json(Map.of(
                        "message", "Account created successfully",
                        "username", username,
                        "profile", publicProfile(usersDatabase.get(username))
                ));
            } catch (IllegalArgumentException exception) {
                ctx.status(400).json(Map.of("error", exception.getMessage()));
            }
        });

        // Login endpoint with password verification
        app.post("/api/login", ctx -> {
            try {
                Map<String, Object> request = requestBody(ctx);
                String username = required(request, "username");
                String password = required(request, "password");
                Map<String, Object> user = usersDatabase.get(username);

                if (user == null) {
                    ctx.status(401).json(Map.of("error", "Invalid username or password"));
                    return;
                }

                String hashedPassword = (String) user.get("passwordHash");
                if (!BCrypt.checkpw(password, hashedPassword)) {
                    ctx.status(401).json(Map.of("error", "Invalid username or password"));
                    return;
                }

                // Establish authenticated server session
                ctx.sessionAttribute("currentUser", username);

                ctx.json(Map.of(
                        "message", "Login successful",
                        "username", username,
                        "profile", publicProfile(user)
                ));
            } catch (IllegalArgumentException exception) {
                ctx.status(400).json(Map.of("error", exception.getMessage()));
            }
        });

        // Logout route clearing session
        app.post("/api/logout", ctx -> {
            ctx.req().getSession().invalidate();
            ctx.json(Map.of("message", "Logged out successfully"));
        });

        // Protected Emergency Freeze
        app.post("/api/freeze-account", ctx -> {
            String loggedInUser = ctx.sessionAttribute("currentUser");
            if (loggedInUser == null) {
                ctx.status(401).json(Map.of("error", "Authentication required"));
                return;
            }

            synchronized (usersDatabase) {
                Map<String, Object> user = usersDatabase.get(loggedInUser);
                if (user != null) {
                    user.put("isFrozen", true);
                    ctx.req().getSession().invalidate(); // Destroy session upon freezing
                    ctx.json(Map.of("message", "Account frozen successfully"));
                } else {
                    ctx.status(404).json(Map.of("error", "User not found"));
                }
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

        app.start(port);
    }

    private static void seedDefaultUser() {
        if (!usersDatabase.isEmpty()) {
            return;
        }
        Map<String, Object> defaultUser = new LinkedHashMap<>();
        defaultUser.put("passwordHash", BCrypt.hashpw("password123", BCrypt.gensalt()));
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
        String rawPassword = required(request, "password");
        user.put("passwordHash", BCrypt.hashpw(rawPassword, BCrypt.gensalt()));
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
        profile.remove("passwordHash");
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
        return schemes;
    }

    private static Map<String, Object> scheme(String name, List<String> requiredDocuments, String instructions, String videoUrl) {
        Map<String, Object> scheme = new LinkedHashMap<>();
        scheme.put("name", name);
        scheme.put("schemeName", name);
        scheme.put("requiredDocuments", requiredDocuments);
        scheme.put("instructions", instructions);
        scheme.put("videoUrl", videoUrl);
        return scheme;
    }
}

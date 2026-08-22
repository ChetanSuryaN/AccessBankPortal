package com.example;

import io.javalin.Javalin;
import io.javalin.http.Context;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public final class Main {
    private static final Map<String, Map<String, Object>> usersDatabase = new HashMap<>();
    private static final Map<String, LockoutRecord> lockoutDatabase = new HashMap<>();
    private static final Map<String, List<Map<String, Object>>> userApplications = new HashMap<>();
    private static final String SESSION_USER_KEY = "currentUser";

    // Regular Expression Validation Patterns
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{4,24}$");
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&#^()_+\\-=])[A-Za-z\\d@$!%*?&#^()_+\\-=]{8,32}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,64}$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^(\\+91[- ]?)?[6-9]\\d{9}$");
    private static final Pattern GOV_ID_PATTERN = Pattern.compile("^([A-Z]{5}[0-9]{4}[A-Z]{1}|\\d{12}|[A-Z][0-9]{7}|GOV-ID-[A-Za-z0-9_-]{4,20})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FULL_NAME_PATTERN = Pattern.compile("^[a-zA-Z\\s.]{3,60}$");

    private static final int MAX_FAILED_ATTEMPTS = 3;
    private static final long LOCKOUT_DURATION_MS = 5 * 60 * 1000; // 5 minutes

    private static class LockoutRecord {
        int failedAttempts = 0;
        long lockoutUntil = 0;
    }

    private Main() {
    }

    public static void main(String[] args) {
        seedDefaultUser();

        int port = 7070;
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.isBlank()) {
            port = Integer.parseInt(envPort);
        }

        Javalin app = Javalin.create(config -> {
            config.http.defaultContentType = "application/json; charset=UTF-8";
        });

        // Public Web Pages
        app.get("/", ctx -> ctx.contentType("text/html; charset=UTF-8").html(frontend()));
        app.get("/index.html", ctx -> ctx.contentType("text/html; charset=UTF-8").html(frontend()));

        // Public All Schemes Summary Endpoint (10 Schemes)
        app.get("/api/schemes", ctx -> {
            String lang = ctx.queryParam("lang");
            if (lang == null || lang.isBlank()) lang = "en";

            Map<String, Map<String, Object>> langSchemes = localizedSchemes().getOrDefault(lang, localizedSchemes().get("en"));
            List<Map<String, Object>> list = new ArrayList<>();
            for (Map.Entry<String, Map<String, Object>> entry : langSchemes.entrySet()) {
                Map<String, Object> item = new LinkedHashMap<>(entry.getValue());
                item.put("id", entry.getKey());
                list.add(item);
            }
            ctx.json(list);
        });

        // Public Specific Scheme Endpoint
        app.get("/api/scheme/{schemeId}", ctx -> {
            String schemeId = ctx.pathParam("schemeId");
            String lang = ctx.queryParam("lang");
            if (lang == null || lang.isBlank()) {
                lang = "en";
            }

            Map<String, Map<String, Object>> langSchemes = localizedSchemes().getOrDefault(lang, localizedSchemes().get("en"));
            Map<String, Object> scheme = langSchemes.get(schemeId);

            if (scheme == null) {
                ctx.status(404).json(Map.of("error", "Scheme not found"));
                return;
            }

            ctx.json(scheme);
        });

        // Multilingual AI Banking Chatbot Endpoint
        app.post("/api/chat", ctx -> {
            Map<String, Object> request = requestBody(ctx);
            String message = request.getOrDefault("message", "").toString().trim();
            String lang = request.getOrDefault("lang", "en").toString().trim().toLowerCase(Locale.ROOT);
            String loggedInUser = ctx.sessionAttribute(SESSION_USER_KEY);

            Map<String, Object> aiResponse = processChatbotMessage(message, lang, loggedInUser);
            ctx.json(aiResponse);
        });

        // Public Authentication Endpoints
        app.post("/api/signup", ctx -> {
            try {
                Map<String, Object> request = requestBody(ctx);
                validateRegistrationRequest(request);

                String username = required(request, "username").trim().toLowerCase();

                synchronized (usersDatabase) {
                    if (usersDatabase.containsKey(username)) {
                        ctx.status(400).json(Map.of("error", "Username is already registered. Please choose another username."));
                        return;
                    }
                    usersDatabase.put(username, userRecord(request));
                }

                ctx.sessionAttribute(SESSION_USER_KEY, username);

                ctx.status(201).json(Map.of(
                        "message", "Account registered and KYC verified successfully",
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
                String username = required(request, "username").trim().toLowerCase();
                String password = required(request, "password");

                long currentTime = System.currentTimeMillis();

                synchronized (lockoutDatabase) {
                    LockoutRecord lockout = lockoutDatabase.get(username);
                    if (lockout != null && lockout.lockoutUntil > currentTime) {
                        long remainingSeconds = (lockout.lockoutUntil - currentTime) / 1000;
                        ctx.status(429).json(Map.of("error", "Account is temporarily locked due to repeated failed login attempts. Please retry in " + remainingSeconds + " seconds."));
                        return;
                    }
                }

                Map<String, Object> user = usersDatabase.get(username);
                boolean authenticated = false;

                if (user != null) {
                    String storedHash = (String) user.get("passwordHash");
                    if (storedHash != null && verifyPassword(password, storedHash)) {
                        authenticated = true;
                    }
                }

                if (!authenticated) {
                    synchronized (lockoutDatabase) {
                        LockoutRecord lockout = lockoutDatabase.computeIfAbsent(username, k -> new LockoutRecord());
                        lockout.failedAttempts++;

                        if (lockout.failedAttempts >= MAX_FAILED_ATTEMPTS) {
                            lockout.lockoutUntil = currentTime + LOCKOUT_DURATION_MS;
                            ctx.status(429).json(Map.of("error", "Security alert: 3 consecutive failed login attempts detected. Account locked for 5 minutes."));
                            return;
                        }

                        int remainingAttempts = MAX_FAILED_ATTEMPTS - lockout.failedAttempts;
                        ctx.status(401).json(Map.of("error", "Invalid credentials. " + remainingAttempts + " attempt(s) remaining before account lockout."));
                        return;
                    }
                }

                synchronized (lockoutDatabase) {
                    lockoutDatabase.remove(username);
                }

                ctx.sessionAttribute(SESSION_USER_KEY, username);

                ctx.json(Map.of(
                        "message", "Login authenticated successfully",
                        "username", username,
                        "profile", publicProfile(user)
                ));
            } catch (IllegalArgumentException exception) {
                ctx.status(400).json(Map.of("error", exception.getMessage()));
            }
        });

        app.post("/api/logout", ctx -> {
            if (ctx.req().getSession(false) != null) {
                ctx.req().getSession().invalidate();
            }
            ctx.json(Map.of("message", "Logged out successfully"));
        });

        // Protected Endpoints
        app.before("/api/protected/*", Main::requireAuthentication);

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
            String lang = ctx.queryParam("lang");
            if (lang == null || lang.isBlank()) {
                lang = "en";
            }

            Map<String, Object> response = new HashMap<>();
            response.put("balance", "₹2,45,800.00");
            response.put("cibil", getLocalizedCibil(lang));
            response.put("profile", publicProfile(user));
            response.put("history", getLocalizedHistory(lang));
            response.put("applications", userApplications.getOrDefault(loggedInUser, List.of()));

            ctx.json(response);
        });

        // Scheme Application Submission Endpoint
        app.post("/api/protected/apply-scheme", ctx -> {
            String loggedInUser = ctx.sessionAttribute(SESSION_USER_KEY);
            Map<String, Object> request = requestBody(ctx);

            String schemeId = required(request, "schemeId");
            String applicantName = required(request, "applicantName");
            String govId = required(request, "govId");
            String remarks = request.getOrDefault("remarks", "Application submitted via citizen portal").toString();
            String documentUploaded = request.getOrDefault("documentUploaded", "Scanned KYC Card").toString();

            String refNo = "REF-" + schemeId.toUpperCase() + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

            Map<String, Object> application = new LinkedHashMap<>();
            application.put("applicationId", refNo);
            application.put("schemeId", schemeId);
            application.put("applicantName", applicantName);
            application.put("govId", govId);
            application.put("status", "SUBMITTED_VERIFIED");
            application.put("submissionDate", LocalDate.now().toString());
            application.put("documentUploaded", documentUploaded);
            application.put("remarks", remarks);

            synchronized (userApplications) {
                userApplications.computeIfAbsent(loggedInUser, k -> new ArrayList<>()).add(0, application);
            }

            ctx.status(201).json(Map.of(
                    "message", "Scheme Application Submitted Successfully",
                    "applicationId", refNo,
                    "status", "VERIFIED_PROCESSING",
                    "details", application
            ));
        });

        app.start(port);
    }

    private static void requireAuthentication(Context ctx) {
        String loggedInUser = ctx.sessionAttribute(SESSION_USER_KEY);
        if (loggedInUser == null || !usersDatabase.containsKey(loggedInUser)) {
            ctx.status(401).json(Map.of("error", "Unauthorized: Please log in to access this protected banking resource."));
        }
    }

    // =========================================================================
    // MULTILINGUAL AI CHATBOT INTELLIGENCE ENGINE (10 SCHEMES & BANKING)
    // =========================================================================
    private static Map<String, Object> processChatbotMessage(String msg, String lang, String loggedInUser) {
        String query = (msg == null ? "" : msg.toLowerCase(Locale.ROOT)).trim();
        Map<String, Object> result = new LinkedHashMap<>();

        boolean isAuth = loggedInUser != null && usersDatabase.containsKey(loggedInUser);
        Map<String, Object> user = isAuth ? usersDatabase.get(loggedInUser) : null;
        String userName = isAuth && user != null ? user.getOrDefault("fullName", "Customer").toString() : "Customer";

        // Balance & Account Details
        if (containsAny(query, "balance", "money", "funds", "बैलेंस", "राशि", "ಖಾತೆ", "ಬಾಕಿ", "ಹಣ", "ನಿಲ್వ", "ధనం", "இருப்பு", "பணம்")) {
            if (!isAuth) {
                result.put("reply", getLocalizedChat(lang,
                        "Please log in to your AccessBank account to view your live balance and transaction history.",
                        "कृपया अपना लाइव बैलेंस और लेनदेन इतिहास देखने के लिए अपने खाते में लॉग इन करें।",
                        "ನಿಮ್ಮ ಲೈವ್ ಬ್ಯಾಲೆನ್ಸ್ ಮತ್ತು ವಹಿವಾಟು ವಿವರ ನೋಡಲು ದಯವಿಟ್ಟು ನಿಮ್ಮ ಖಾತೆಗೆ ಲಾಗಿನ್ ಮಾಡಿ.",
                        "మీ ఖాతా బ్యాలెన్స్ మరియు లావాదేవీల రికార్డును చూడటానికి దయచేసి లాగిన్ అవ్వండి.",
                        "உங்கள் நேரலை இருப்பு மற்றும் பரிவர்த்தனை வரலாற்றைப் பார்க்க தயவுசெய்து உள்நுழையவும்."));
            } else {
                result.put("reply", getLocalizedChat(lang,
                        "Hello " + userName + "! Your current ledger net balance is ₹2,45,800.00. Your CIBIL score is 785 (Excellent).",
                        "नमस्ते " + userName + "! आपकी वर्तमान शुद्ध खाता शेष राशि ₹2,45,800.00 है। आपका सिबिल स्कोर 785 (उत्कृष्ट) है।",
                        "ನಮಸ್ಕಾರ " + userName + "! ನಿಮ್ಮ ಪ್ರಸ್ತುತ ಒಟ್ಟು ಉಳಿತಾಯ ಬ್ಯಾಲೆನ್ಸ್ ₹2,45,800.00 ಆಗಿದೆ. ನಿಮ್ಮ ಸಿಬಿಲ್ ಸ್ಕೋರ್ 785 (ಉತ್ತಮ ಶ್ರೇಣಿ).",
                        "నమస్కారం " + userName + "! మీ ప్రస్తుత నికర ఖాతా నిల్వ ₹2,45,800.00. మీ సిబిల్ స్కోరు 785 (అత్యుత్తమం).",
                        "வணக்கம் " + userName + "! உங்கள் தற்போதைய கணக்கு இருப்பு ₹2,45,800.00. உங்கள் சிபில் மதிப்பீடு 785 (மிகச் சிறந்தது)."));
            }
            result.put("action", "show_balance");
            return result;
        }

        // Scheme: PM Kisan (Agriculture)
        if (containsAny(query, "kisan", "farmer", "agriculture", "किसान", "ಕಿಸಾನ್", "ರೈತ", "రైతు", "விவசாயி")) {
            result.put("reply", getLocalizedChat(lang,
                    "🌾 PM-Kisan Samman Nidhi provides ₹6,000 per year direct income support in 3 equal installments to landholding farmer families. Required: Aadhaar, Land ownership papers, and Bank account.",
                    "🌾 पीएम-किसान सम्मान निधि योजना के तहत पात्र किसान परिवारों को प्रति वर्ष ₹6,000 की वित्तीय सहायता 3 किस्तों में दी जाती है। आवश्यक: आधार, भूमि दस्तावेज और बैंक खाता।",
                    "🌾 ಪಿಎಂ ಕಿಸಾನ್ ಯೋಜನೆಯಡಿ ಅರ್ಹ ರೈತ ಕುಟುಂಬಗಳಿಗೆ ವರ್ಷಕ್ಕೆ ₹6,000 ನೇರ ಆರ್ಥಿಕ ನೆರವು ನೀಡಲಾಗುತ್ತದೆ. ಅಗತ್ಯ ದಾಖಲೆಗಳು: ಆಧಾರ್ ಕಾರ್ಡ್, ಜಮೀನಿನ ಪಹಣಿ (RTC) ಮತ್ತು ಬ್ಯಾಂಕ್ ಪಾಸ್‌ಬುಕ್.",
                    "🌾 పీఎం కిసాన్ సమ్మాన్ నిధి ద్వారా అర్హులైన రైతులకు ఏడాదికి ₹6,000 నేరుగా ఖాతాలో జమ చేయబడుతుంది. అవసరమైన పత్రాలు: ఆధార్, భూమి యాజమాన్య పత్రాలు, బ్యాంక్ ఖాతా.",
                    "🌾 பிஎம் கிசான் திட்டத்தின் கீழ் தகுதியான விவசாயிகளுக்கு ஆண்டுதோறும் ரூ. 6,000 நிதி உதவி வழங்கப்படுகிறது. தேவையானவை: ஆதார், நில ஆவணங்கள் மற்றும் வங்கி கணக்கு."));
            result.put("action", "show_scheme_pm-kisan");
            return result;
        }

        // Scheme: PM Mudra (Business)
        if (containsAny(query, "mudra", "business loan", "msme", "मुद्रा", "ಮುದ್ರಾ", "ವ್ಯಾಪಾರ ಸಾಲ", "ముద్రా", "முத்ரா")) {
            result.put("reply", getLocalizedChat(lang,
                    "💼 PM MUDRA Yojana provides micro-business loans up to ₹10 Lakhs (Shishu, Kishore, Tarun) without collateral for entrepreneurs. Required: Business proposal, Govt ID, Address proof, 6-month bank statement.",
                    "💼 पीएम मुद्रा योजना सूक्ष्म उद्योगों और उद्यमियों को ₹10 लाख तक का बिना गारंटी का ऋण प्रदान करती है। आवश्यक: व्यापार प्रस्ताव, सरकारी आईडी, पता प्रमाण और बैंक विवरण।",
                    "💼 ಪ್ರಧಾನಮಂತ್ರಿ ಮುದ್ರಾ ಯೋಜನೆಯು ಸಣ್ಣ ಉದ್ಯಮಿಗಳಿಗೆ ಯಾವುದೇ ಆಸ್ತಿ ಅಡಮಾನವಿಲ್ಲದೆ ₹10 ಲಕ್ಷದವರೆಗೆ ಸಾಲ ಸೌಲಭ್ಯ ನೀಡುತ್ತದೆ. ಅಗತ್ಯ: ಉದ್ಯಮ ಯೋಜನೆ, ಗುರುತಿನ ಚೀಟಿ ಮತ್ತು ಬ್ಯಾಂಕ್ ಸ್ಟೇಟ್‌ಮೆಂಟ್.",
                    "💼 పీఎం ముద్రా యోజన కింద చిన్న వ్యాపారులకు ₹10 లక్షల వరకు పూచీకత్తు లేని వ్యాపార రుణాలు లభిస్తాయి. అవసరమైన పత్రాలు: వ్యాపార ప్రణాళిక, ఆధార్/పాన్ మరియు బ్యాంక్ స్టేట్‌మెంట్.",
                    "💼 பிரதான் மந்திரி முத்ரா திட்டம் தொழில் முனைவோருக்கு ரூ. 10 லட்சம் வரை பிணையமற்ற குறுந்தொழில் கடன் வழங்குகிறது. தேவையானவை: தொழில் திட்டம், அரசு அடையாள அட்டை, வங்கி கணக்கு."));
            result.put("action", "show_scheme_pm-mudra");
            return result;
        }

        // Scheme: PM Awas (Housing)
        if (containsAny(query, "awas", "housing", "home loan", "आवास", "घर", "ಆವಾಸ್", "ಮನೆ", "గృహ", "வீடு", "ஆவாஸ்")) {
            result.put("reply", getLocalizedChat(lang,
                    "🏠 PM Awas Yojana (PMAY) provides credit-linked interest subsidies up to ₹2.67 Lakhs on home loans for affordable housing construction/purchase.",
                    "🏠 प्रधानमंत्री आवास योजना (PMAY) घर खरीदने या बनाने के लिए होम लोन पर ₹2.67 लाख तक की ब्याज सब्सिडी प्रदान करती है।",
                    "🏠 ಪ್ರಧಾನಮಂತ್ರಿ ಆವಾಸ್ ಯೋಜನೆ (PMAY) ಕೈಗೆಟುಕುವ ದರದಲ್ಲಿ ಸ್ವಂತ ಮನೆ ನಿರ್ಮಾಣಕ್ಕಾಗಿ ಗೃಹ ಸಾಲದ ಮೇಲೆ ₹2.67 ಲಕ್ಷದವರೆಗೆ ಬಡ್ಡಿ ಸಬ್ಸಿಡಿ ಒದಗಿಸುತ್ತದೆ.",
                    "🏠 పీఎం ఆవాస్ యోజన సొంత ఇంటి నిర్మాణానికి లేదా కొనుగోలుకు గృహ రుణాలపై ₹2.67 లక్షల వరకు వడ్డీ సబ్సిడీని అందిస్తుంది.",
                    "🏠 பிரதான் மந்திரி ஆவாஸ் திட்டம் சொந்த வீடு கட்ட அல்லது வாங்க வீட்டுக் கடன்களுக்கு ரூ. 2.67 லட்சம் வரை வட்டி மானியம் வழங்குகிறது."));
            result.put("action", "show_scheme_pm-awas");
            return result;
        }

        // Scheme: Sukanya Samriddhi (Girl Child)
        if (containsAny(query, "sukanya", "girl child", "daughter", "सुकन्या", "ಕನ್ಯಾ", "ಹೆಣ್ಣು ಮಗು", "బాలిక", "பெண் குழந்தை", "சுகன்யா")) {
            result.put("reply", getLocalizedChat(lang,
                    "🌸 Sukanya Samriddhi Yojana (SSY) is a dedicated high-interest savings scheme (8.2% p.a.) with tax benefits under 80C for girl children below 10 years of age.",
                    "🌸 सुकन्या समृद्धि योजना 10 वर्ष से कम आयु की बालिकाओं के लिए 8.2% उच्च ब्याज और आयकर छूट (धारा 80C) वाली बचत योजना है।",
                    "🌸 ಸುಕನ್ಯಾ ಸಮೃದ್ಧಿ ಯೋಜನೆಯು 10 ವರ್ಷದೊಳಗಿನ ಹೆಣ್ಣುಮಕ್ಕಳ ಭವಿಷ್ಯಕ್ಕಾಗಿ ವಾರ್ಷಿಕ 8.2% ಆಕರ್ಷಕ ಬಡ್ಡಿ ಮತ್ತು ತೆರಿಗೆ ವಿನಾಯಿತಿ ನೀಡುವ ಯೋಜನೆಯಾಗಿದೆ.",
                    "🌸 సుకన్య సమృద్ధి యోజన 10 ఏళ్లలోపు ఆడపిల్లల కోసం 8.2% అధిక వడ్డీ మరియు పన్ను ప్రయోజనాలను అందించే పథకం.",
                    "🌸 சுகன்யா சம்ரித்தி திட்டம் 10 வயதுக்குட்பட்ட பெண் குழந்தைகளின் எதிர்காலத்திற்காக 8.2% உயர் வட்டி மற்றும் வரி விலக்கு அளிக்கும் சிறப்பு சேமிப்பு திட்டமாகும்."));
            result.put("action", "show_scheme_sukanya-samriddhi");
            return result;
        }

        // Scheme: Atal Pension
        if (containsAny(query, "atal", "pension", "retirement", "अटल", "पेंशन", "ಪಿಂಚಣಿ", "పెన్షన్", "ஓய்வூதியம்")) {
            result.put("reply", getLocalizedChat(lang,
                    "🛡️ Atal Pension Yojana (APY) guarantees a monthly pension of ₹1,000 to ₹5,000 after age 60 for unorganized sector citizens (entry age 18-40 years).",
                    "🛡️ अटल पेंशन योजना (APY) 18 से 40 वर्ष के नागरिकों को 60 वर्ष की आयु के बाद ₹1,000 से ₹5,000 तक की मासिक पेंशन की गारंटी देती है।",
                    "🛡️ ಅಟಲ್ ಪಿಂಚಣಿ ಯೋಜನೆಯು 60 ವರ್ಷ ತುಂಬಿದ ನಂತರ ತಿಂಗಳಿಗೆ ₹1,000 ರಿಂದ ₹5,000 ಖಚಿತ ಮಾಸಿಕ ಪಿಂಚಣಿಯನ್ನು ಖಾತರಿಪಡಿಸುತ್ತದೆ (ವಯೋಮಿತಿ 18-40).",
                    "🛡️ అటల్ పెన్షన్ యోజన 60 ఏళ్ల తర్వాత నెలకు ₹1,000 నుండి ₹5,000 వరకు ఖచ్చితమైన పెన్షన్‌ను అందిస్తుంది (ప్రవేశ వయస్సు 18-40 సం).",
                    "🛡️ அடல் ஓய்வூதியத் திட்டம் 60 வயதிற்குப் பிறகு மாதம் ரூ. 1,000 முதல் ரூ. 5,000 வரை உத்தரவாதமான மாதாந்திர ஓய்வூதியம் வழங்குகிறது."));
            result.put("action", "show_scheme_atal-pension");
            return result;
        }

        // Scheme: Ayushman Bharat (Health)
        if (containsAny(query, "ayushman", "health", "hospital", "medical", "आयुष्मान", "स्वास्थ्य", "ಆರೋಗ್ಯ", "వైద్య", "மருத்துவம்", "ஆயுஷ்மான்")) {
            result.put("reply", getLocalizedChat(lang,
                    "🏥 Ayushman Bharat (PM-JAY) provides cashless health protection coverage up to ₹5 Lakhs per family per year for secondary and tertiary hospital care.",
                    "🏥 आयुष्मान भारत योजना (PM-JAY) पात्र परिवारों को माध्यमिक और तृतीयक स्तर के अस्पताल में इलाज के लिए प्रति वर्ष ₹5 लाख का कैशलेस स्वास्थ्य बीमा देती है।",
                    "🏥 ಆಯುಷ್ಮಾನ್ ಭಾರತ್ ಯೋಜನೆಯಡಿ ಅರ್ಹ ಕುಟುಂಬಗಳಿಗೆ ಪ್ರತಿ ವರ್ಷ ₹5 ಲಕ್ಷದವರೆಗೆ ಆಸ್ಪತ್ರೆಗಳಲ್ಲಿ ನಗದು ರಹಿತ ಉಚಿತ ಚಿಕಿತ್ಸೆ ಒದಗಿಸಲಾಗುತ್ತದೆ.",
                    "🏥 ఆయుష్మాన్ భారత్ పథకం ద్వారా అర్హులైన కుటుంబాలకు సంవత్సరానికి ₹5 లక్షల వరకు ఉచిత వైద్య చికిత్స మరియు హాస్పిటల్ కవరేజ్ లభిస్తుంది.",
                    "🏥 ஆயுஷ்மான் பாரத் திட்டம் தகுதியான குடும்பங்களுக்கு ஆண்டுக்கு ரூ. 5 லட்சம் வரை இலவச மருத்துவ சிகிச்சை மற்றும் காப்பீடு வழங்குகிறது."));
            result.put("action", "show_scheme_ayushman-bharat");
            return result;
        }

        // Scheme: Stand-Up India
        if (containsAny(query, "standup", "stand up", "women entrepreneur", "महिला उद्यम", "ಮಹಿಳಾ ಉದ್ಯಮ", "మహిళా", "மகளிர் தொழில்")) {
            result.put("reply", getLocalizedChat(lang,
                    "🚀 Stand-Up India facilitates bank loans between ₹10 Lakhs and ₹1 Crore to at least one SC/ST and one Woman borrower per bank branch for greenfield enterprises.",
                    "🚀 स्टैंड-अप इंडिया योजना महिला और एससी/एसटी उद्यमियों को नए व्यवसाय के लिए ₹10 लाख से ₹1 करोड़ तक का बैंक ऋण उपलब्ध कराती है।",
                    "🚀 ಸ್ಟ್ಯಾಂಡ್-ಅಪ್ ಇಂಡಿಯಾ ಯೋಜನೆಯು ಮಹಿಳಾ ಮತ್ತು SC/ST ಉದ್ಯಮಿಗಳಿಗೆ ಹೊಸ ಉದ್ಯಮ ಸ್ಥಾಪಿಸಲು ₹10 ಲಕ್ಷದಿಂದ ₹1 ಕೋಟಿಯವರೆಗೆ ಸಾಲ ಒದಗಿಸುತ್ತದೆ.",
                    "🚀 స్టాండ్-అప్ ఇండియా పథకం మహిళా మరియు SC/ST ఔత్సాహిక పారిశ్రామికవేత్తలకు ₹10 లక్షల నుండి ₹1 కోటి వరకు రుణ సహాయం అందిస్తుంది.",
                    "🚀 ஸ்டாண்ட்-அப் இந்தியா திட்டம் மகளிர் மற்றும் SC/ST தொழில் முனைவோருக்கு புதிய தொழில்களுக்கு ரூ. 10 லட்சம் முதல் ரூ. 1 கோடி வரை கடன் வழங்குகிறது."));
            result.put("action", "show_scheme_stand-up-india");
            return result;
        }

        // Scheme: PM SVANidhi (Street Vendors)
        if (containsAny(query, "svanidhi", "vendor", "street", "स्वनिधि", "ರೆಹಡಿ", "ಕಿರು ಸಾಲ", "స్ట్రీట్ వెండర్", "சிறு வணிகர்")) {
            result.put("reply", getLocalizedChat(lang,
                    "🛒 PM SVANidhi Scheme offers working capital micro-loans of ₹10,000, ₹20,000, and ₹50,000 with 7% interest subsidy for urban and rural street vendors.",
                    "🛒 पीएम स्वनिधि योजना स्ट्रीट वेंडर्स को ₹10,000 से ₹50,000 तक का कार्यशील पूंजी ऋण 7% ब्याज सब्सिडी के साथ देती है।",
                    "🛒 ಪಿಎಂ ಸ್ವನಿಧಿ ಯೋಜನೆಯು ಬೀದಿ ಬದಿ ವ್ಯಾಪಾರಿಗಳಿಗೆ ₹10,000 ರಿಂದ ₹50,000 ವರೆಗೆ ಶೇ. 7 ರಷ್ಟು ಬಡ್ಡಿ ರಿಯಾಯಿತಿಯೊಂದಿಗೆ ಕಿರು ಸಾಲ ಒದಗಿಸುತ್ತದೆ.",
                    "🛒 పీఎం స్వనిధి పథకం వీధి వ్యాపారులకు ₹10,000 నుండి ₹50,000 వరకు 7% వడ్డీ రాయితీతో వర్కింగ్ క్యాపిటల్ రుణాలను అందిస్తుంది.",
                    "🛒 பிஎம் ஸ்வாநிதி திட்டம் தெருவோர வியாபாரிகளுக்கு ரூ. 10,000 முதல் ரூ. 50,000 வரை 7% வட்டி மானியத்துடன் மூலதனக் கடன் வழங்குகிறது."));
            result.put("action", "show_scheme_pm-svanidhi");
            return result;
        }

        // Scheme: Senior Citizen
        if (containsAny(query, "senior", "elder", "वरिष्ठ", "ಬುಜೋರ್ಗ್", "ಹಿರಿಯ", "సీనియర్", "மூத்த")) {
            result.put("reply", getLocalizedChat(lang,
                    "👴 Senior Citizen Benefits Scheme provides higher deposit interest (+0.75%) and dedicated healthcare concessions. Required: Government ID, Proof of age, Address proof, and recent photo.",
                    "👴 वरिष्ठ नागरिक कल्याण योजना में सावधि जमा पर उच्च ब्याज (+0.75%) और रियायतें मिलती हैं। आवश्यक: सरकारी आईडी, आयु प्रमाण, पता प्रमाण और पासपोर्ट फोटो।",
                    "👴 ಹಿರಿಯ ನಾಗರಿಕರ ಕಲ್ಯಾಣ ಯೋಜನೆಯು ಠೇವಣಿಗಳ ಮೇಲೆ ಹೆಚ್ಚಿನ ಬಡ್ಡಿ (+0.75%) ಮತ್ತು ಆರೋಗ್ಯ ಸೌಲಭ್ಯಗಳನ್ನು ನೀಡುತ್ತದೆ. ಅಗತ್ಯ: ಸರ್ಕಾರಿ ಗುರುತಿನ ಚೀಟಿ, ವಯಸ್ಸಿನ ಪುರಾವೆ, ವಿಳಾಸ ದಾಖಲೆ.",
                    "👴 సీనియర్ సిటిజన్ సంಕ್ಷేಮ పథకం డిపాజిట్లపై అదనపు వడ్డీ (+0.75%) మరియు వైద్య ప్రయోజనాలను అందిస్తుంది. అవసరం: గుర్తింపు కార్డు, వయస్సు ధృవీకరణ.",
                    "👴 மூத்த குடிமக்கள் நலத் திட்டம் கூடுதல் வட்டி (+0.75%) மற்றும் சிறப்பு சலுகைகளை வழங்குகிறது. தேவையானவை: அரசு அடையாள அட்டை, வயதுச் சான்றிதழ்."));
            result.put("action", "show_scheme_senior-citizen");
            return result;
        }

        // Scheme: Student Loan
        if (containsAny(query, "student", "college loan", "छात्र", "ವಿದ್ಯಾರ್ಥಿ", "విద్యార్థి", "மாணவர் கடன்")) {
            result.put("reply", getLocalizedChat(lang,
                    "🎓 Student Loan Assistance Scheme offers collateral-free loans up to ₹10 Lakhs at 6.8% interest. Required: Government ID, College Admission Letter, Transcripts, Income Certificate.",
                    "🎓 छात्र शिक्षा ऋण सहायता योजना ₹10 लाख तक का बिना गारंटी का ऋण 6.8% ब्याज दर पर प्रदान करती है। आवश्यक: सरकारी आईडी, प्रवेश पत्र, अंकतालिका और आय प्रमाण पत्र।",
                    "🎓 ವಿದ್ಯಾರ್ಥಿ ಸಾಲ ನೆರವು ಯೋಜನೆಯು 6.8% ಬಡ್ಡಿ ದರದಲ್ಲಿ ₹10 ಲಕ್ಷದವರೆಗೆ ಜಾಮೀನು ರಹಿತ ಶೈಕ್ಷಣಿಕ ಸಾಲ ಒದಗಿಸುತ್ತದೆ. ಅಗತ್ಯ: ಪ್ರವೇಶ ಪತ್ರ, ಅಂಕಪಟ್ಟಿ, ಆದಾಯ ಪ್ರಮಾಣಪತ್ರ.",
                    "🎓 విద్యార్థి విద్యా రుణ సహాయ పథకం 6.8% వడ్డీతో రూ. 10 లక్షల వరకు పూచీకత్తు లేని విద్యా రుణాలను అందిస్తుంది. అవసరం: అడ్మిషన్ లెటర్, మార్కుల జాబితా.",
                    "🎓 மாணவர் கல்விக் கடன் திட்டம் 6.8% வட்டி விகிதத்தில் ரூ. 10 லட்சம் வரை பிணையமற்ற கடன் வழங்குகிறது. தேவையானவை: சேர்க்கை கடிதம், மதிப்பெண் சான்றிதழ்."));
            result.put("action", "show_scheme_student-loan");
            return result;
        }

        // Emergency Freeze / Fraud
        if (containsAny(query, "freeze", "scam", "fraud", "hack", "stolen", "lost", "ब्लॉक", "धोखा", "ಫ್ರೀಜ್", "ವಂಚನೆ", "ಕಳವು", "మోసం", "లాక్", "முடக்கு", "மோசடி")) {
            result.put("reply", getLocalizedChat(lang,
                    "🚨 URGENT: If you suspect fraudulent activity or unauthorized debit, click 'EMERGENCY: FREEZE MY ACCOUNT NOW' below immediately to lock all transactions instantly!",
                    "🚨 आपातकालीन सहायता: यदि आपको किसी धोखाधड़ी या अनधिकृत लेनदेन का संदेह है, तो अपने खाते को तुरंत सुरक्षित करने के लिए 'खाता तुरंत फ़्रीज़ करें' बटन पर क्लिक करें!",
                    "🚨 ತುರ್ತು ಎಚ್ಚರಿಕೆ: ಯಾವುದೇ ವಂಚನೆ ಕಂಡುಬಂದಲ್ಲಿ, ನಿಮ್ಮ ಖಾತೆಯನ್ನು ತಕ್ಷಣ ಲಾಕ್ ಮಾಡಲು ಕೆಳಗಿನ 'ಖಾತೆಯನ್ನು ಸ್ಥಗಿತಗೊಳಿಸಿ (Freeze)' ಬಟನ್ ಕ್ಲಿಕ್ ಮಾಡಿ!",
                    "🚨 ಅತ್ಯవసర సహాయం: ఏదైనా మోసం జరిగినట్లు అనుమానం ఉంటే, వెంటనే అన్ని లావాదేవీలను నిలిపివేయడానికి 'ఖాతాను స్తంభింపజేయండి' బటన్ నొక్కండి!",
                    "🚨 அவசர உதவி: மோசடி நடந்ததாக சந்தேகம் இருந்தால், உடனடியாக கணக்கை முடக்க 'கணக்கை முடக்கு' பொத்தானை அழுத்தவும்!"));
            result.put("action", "show_freeze");
            return result;
        }

        // KYC & Document Scanning
        if (containsAny(query, "scan", "document", "kyc", "upload", "स्कैन", "दस्तावेज", "ಸ್ಕ್ಯಾನ್", "ದಾಖಲೆ", "స్కాన్", "పత్రం", "ஸ்கேன்", "ஆவணம்")) {
            result.put("reply", getLocalizedChat(lang,
                    "📷 Document Scanning Feature: You can click 'Scan Document' to auto-extract details from Aadhaar, PAN, or Passport to fill the signup or scheme application form in seconds!",
                    "📷 दस्तावेज़ स्कैन सुविधा: आप आधार, पैन या पासपोर्ट से विवरण स्वतः भरकर पंजीकरण या योजना आवेदन फॉर्म सेकंडों में भरने के लिए 'दस्तावेज़ स्कैन करें' पर क्लिक कर सकते हैं!",
                    "📷 ದಾಖಲೆ ಸ್ಕ್ಯಾನಿಂಗ್ ಸೌಲಭ್ಯ: ಆಧಾರ್, ಪ್ಯಾನ್ ಅಥವಾ ಪಾಸ್‌ಪೋರ್ಟ್‌ನಿಂದ ವಿವರಗಳನ್ನು ಸ್ವಯಂಚಾಲಿತವಾಗಿ ಪಡೆದು ಅರ್ಜಿ ತುಂಬಲು ನೀವು 'ದಾಖಲೆ ಸ್ಕ್ಯಾನ್ ಮಾಡಿ' ಕ್ಲಿಕ್ ಮಾಡಬಹುದು!",
                    "📷 పత్రాల స్కానింగ్ సదుపాయం: ఆధార్, పాన్ లేదా పాస్‌పోర్ట్ నుండి వివరాలను ఆటోమేటిక్‌గా నింపి ఫారమ్‌ను సమర్పించడానికి మీరు 'డాక్యుమెంట్ స్కాన్' ఉపయోగించవచ్చు!",
                    "📷 ஆவண ஸ்கேனிங் வசதி: ஆதார், பான் அல்லது பாஸ்போர்ட்டில் இருந்து விவரங்களை தானாக நிரப்பி விண்ணப்பத்தை பூர்த்தி செய்ய 'ஆவணத்தை ஸ்கேன் செய்' வசதியைப் பயன்படுத்தலாம்!"));
            result.put("action", "none");
            return result;
        }

        // Default Response
        result.put("reply", getLocalizedChat(lang,
                "Hello! I am AccessBot, your 24/7 AI Assistant. Explore all 10 Government Schemes, scan documents for auto-fill, check your balance, or get scam protection guidance.",
                "नमस्ते! मैं एक्सेसबॉट हूँ, आपका 24/7 एआई सहायक। सभी 10 सरकारी योजनाओं की जानकारी लें, दस्तावेज़ स्कैन करके फॉर्म भरें, या बैलेंस जांचें।",
                "ನಮಸ್ಕಾರ! ನಾನು ಆಕ್ಸೆಸ್‌ಬಾಟ್. ಎಲ್ಲಾ 10 ಸರ್ಕಾರಿ ಯೋಜನೆಗಳ ಮಾಹಿತಿ, ದಾಖಲೆ ಸ್ಕ್ಯಾನ್ ಮಾಡಿ ಅರ್ಜಿ ತುಂಬುವಿಕೆ, ಅಥವಾ ಬ್ಯಾಲೆನ್ಸ್ ವಿವರಗಳಿಗಾಗಿ ನಾನು ಸಹಾಯ ಮಾಡಬಲ್ಲೆ.",
                "నమస్కారం! నేను యాక్సెಸ್‌బాట్. మొత్తం 10 ప్రభుత్వ పథకాల వివరాలు, డాక్యుమెంట్ స్కానింగ్ ద్వారా ఫారమ్ ఫిల్లింగ్ మరియు బ్యాలెన్స్ చెక్ కోసం నన్ను అడగండి.",
                "வணக்கம்! நான் அக்செஸ்பாட். அனைத்து 10 அரசு திட்டங்கள், ஆவண ஸ்கேன் மூலம் தானாக விண்ணப்பம் நிரப்புதல் அல்லது இருப்பு விவரங்களை அறிய என்னிடம் கேளுங்கள்."));
        result.put("action", "none");
        return result;
    }

    private static boolean containsAny(String input, String... keywords) {
        for (String k : keywords) {
            if (input.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private static String getLocalizedChat(String lang, String en, String hi, String kn, String te, String ta) {
        return switch (lang) {
            case "hi" -> hi;
            case "kn" -> kn;
            case "te" -> te;
            case "ta" -> ta;
            default -> en;
        };
    }

    // =========================================================================
    // LEGITIMATE USER VALIDATION (KYC & INTEGRITY CHECKS)
    // =========================================================================
    private static void validateRegistrationRequest(Map<String, Object> request) {
        String username = required(request, "username").trim();
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new IllegalArgumentException("Invalid username. It must be 4-24 alphanumeric characters (letters, numbers, underscores).");
        }

        String password = required(request, "password");
        if (!PASSWORD_PATTERN.matcher(password).matches()) {
            throw new IllegalArgumentException("Password does not meet security criteria. It must be 8-32 characters and contain at least 1 uppercase letter, 1 lowercase letter, 1 number, and 1 special symbol (@$!%*?&#^()_+-=).");
        }

        String fullName = required(request, "fullName").trim();
        if (!FULL_NAME_PATTERN.matcher(fullName).matches()) {
            throw new IllegalArgumentException("Invalid Full Name. Please enter a valid legal name (3-60 characters, letters only).");
        }

        String dobStr = required(request, "dateOfBirth").trim();
        try {
            LocalDate dob = LocalDate.parse(dobStr);
            LocalDate today = LocalDate.now();
            if (dob.isAfter(today)) {
                throw new IllegalArgumentException("Date of birth cannot be in the future.");
            }
            int age = Period.between(dob, today).getYears();
            if (age < 18) {
                throw new IllegalArgumentException("Eligibility Requirement: User must be at least 18 years old to hold an independent account (Current age: " + age + ").");
            }
            if (age > 120) {
                throw new IllegalArgumentException("Invalid date of birth. Age exceeds verifiable parameters.");
            }
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date of birth format. Please use standard YYYY-MM-DD format.");
        }

        String email = required(request, "email").trim();
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new IllegalArgumentException("Invalid email address format. Please provide a legitimate email (e.g., user@example.com).");
        }

        String phone = required(request, "phone").trim();
        if (!PHONE_PATTERN.matcher(phone).matches()) {
            throw new IllegalArgumentException("Invalid phone number format. Please provide a legitimate 10-digit mobile number starting with 6, 7, 8, or 9.");
        }

        String govId = required(request, "governmentId").trim();
        if (!GOV_ID_PATTERN.matcher(govId).matches()) {
            throw new IllegalArgumentException("Invalid Government ID format. Accepted: PAN Card (ABCDE1234F), Aadhaar (12 digits), Passport (A1234567), or GOV-ID format.");
        }

        String address = required(request, "address").trim();
        if (address.length() < 5 || address.length() > 200) {
            throw new IllegalArgumentException("Residential address must be between 5 and 200 characters.");
        }
    }

    private static Map<String, Object> userRecord(Map<String, Object> request) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", required(request, "username").trim().toLowerCase());
        user.put("passwordHash", hashPassword(required(request, "password")));
        user.put("fullName", required(request, "fullName").trim());
        user.put("dateOfBirth", required(request, "dateOfBirth").trim());
        user.put("address", required(request, "address").trim());
        user.put("phone", required(request, "phone").trim());
        user.put("email", required(request, "email").trim());
        user.put("governmentId", required(request, "governmentId").trim().toUpperCase());
        user.put("kycStatus", "VERIFIED_CITIZEN");
        user.put("registeredAt", LocalDate.now().toString());
        return user;
    }

    private static Map<String, Object> publicProfile(Map<String, Object> user) {
        if (user == null) {
            return Map.of();
        }
        Map<String, Object> profile = new LinkedHashMap<>(user);
        profile.remove("passwordHash");
        profile.remove("password");
        return profile;
    }

    // =========================================================================
    // CRYPTOGRAPHIC PBKDF2 PASSWORD HASHING & SALTING
    // =========================================================================
    private static String hashPassword(String password) {
        try {
            byte[] salt = new byte[16];
            SecureRandom.getInstanceStrong().nextBytes(salt);
            KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 65536, 128);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = factory.generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("Error during cryptographic password hashing", e);
        }
    }

    private static boolean verifyPassword(String password, String storedHash) {
        try {
            String[] parts = storedHash.split(":");
            if (parts.length != 2) {
                return storedHash.equals(password);
            }
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] expectedHash = Base64.getDecoder().decode(parts[1]);
            KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 65536, 128);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] actualHash = factory.generateSecret(spec).getEncoded();
            return MessageDigest.isEqual(expectedHash, actualHash);
        } catch (Exception e) {
            return false;
        }
    }

    private static void seedDefaultUser() {
        if (!usersDatabase.isEmpty()) {
            return;
        }
        Map<String, Object> defaultUser = new LinkedHashMap<>();
        defaultUser.put("username", "priya123");
        defaultUser.put("passwordHash", hashPassword("Password@123"));
        defaultUser.put("fullName", "Priya Sharma");
        defaultUser.put("dateOfBirth", "1990-01-15");
        defaultUser.put("address", "42 Green Park Road, New Delhi, India");
        defaultUser.put("phone", "+91-9876543210");
        defaultUser.put("email", "priya.sharma@example.com");
        defaultUser.put("governmentId", "ABCDE1234F");
        defaultUser.put("kycStatus", "VERIFIED_CITIZEN");
        defaultUser.put("registeredAt", "2024-01-01");
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
            throw new IllegalArgumentException("Missing required parameter: " + field);
        }
        return value.toString();
    }

    private static String frontend() {
        Path[] candidates = {
                Path.of("index.html"),
                Path.of("../index.html"),
                Path.of("../../index.html"),
                Path.of("src/main/resources/index.html")
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                } catch (java.io.IOException exception) {
                    throw new IllegalStateException("Unable to read frontend file: " + candidate, exception);
                }
            }
        }
        throw new IllegalStateException("Frontend file index.html was not found.");
    }

    private static String getLocalizedCibil(String lang) {
        return switch (lang) {
            case "hi" -> "785 (उत्कृष्ट - सत्यापित नागरिक)";
            case "kn" -> "785 (ಉತ್ತಮ ಶ್ರೇಣಿ - ಪರಿಶೀಲಿಸಿದ ನಾಗರಿಕ)";
            case "te" -> "785 (అత్యుత్తమం - ధృవీకరించబడిన పౌరుడు)";
            case "ta" -> "785 (மிகச் சிறந்தது - சரிபார்க்கப்பட்ட குடிமகன்)";
            default -> "785 (Excellent - Verified Citizen)";
        };
    }

    private static List<String> getLocalizedHistory(String lang) {
        return switch (lang) {
            case "hi" -> List.of(
                    "जमा: +₹10,000.00 (वेतन क्रेडिट)",
                    "स्थानांतरण: -₹1,200.00 (उपयोगिता बिल)",
                    "ब्याज जमा: +₹450.00"
            );
            case "kn" -> List.of(
                    "ಠೇವಣಿ: +₹10,000.00 (ವೇತನ ಜಮೆ)",
                    "ವರ್ಗಾವಣೆ: -₹1,200.00 (ಯುಟಿಲಿಟಿ ಬಿಲ್)",
                    "ಬಡ್ಡಿ ಜಮೆ: +₹450.00"
            );
            case "te" -> List.of(
                    "డిపాజిట్: +₹10,000.00 (జీతం జమ)",
                    "బదిలీ: -₹1,200.00 (కరెంట్/వాటర్ బిల్లు)",
                    "వడ్డీ జమ: +₹450.00"
            );
            case "ta" -> List.of(
                    "வைப்பு: +₹10,000.00 (சம்பள வரவு)",
                    "பரிமாற்றம்: -₹1,200.00 (பயன்பாட்டு கட்டணம்)",
                    "வட்டி வரவு: +₹450.00"
            );
            default -> List.of(
                    "Deposit: +₹10,000.00 (Salary Credit)",
                    "Transfer: -₹1,200.00 (Utility Bill)",
                    "Interest Credited: +₹450.00"
            );
        };
    }

    // =========================================================================
    // 10 GOVERNMENT SCHEMES LOCALIZED IN 5 LANGUAGES
    // =========================================================================
    private static Map<String, Map<String, Map<String, Object>>> localizedSchemes() {
        Map<String, Map<String, Map<String, Object>>> langMap = new LinkedHashMap<>();

        // 1. English
        Map<String, Map<String, Object>> en = new LinkedHashMap<>();
        en.put("senior-citizen", scheme("Senior Citizen Benefits Scheme", List.of("Government ID", "Proof of age", "Address proof", "Recent photograph"), "Submit your identity, age, and address documents through the nearest authorized service center.", "https://example.com/videos/senior-citizen"));
        en.put("student-loan", scheme("Student Loan Assistance Scheme", List.of("Government ID", "Admission letter", "Academic transcripts", "Income certificate"), "Complete the student loan application with your admission and financial documents, then submit it to a participating bank.", "https://example.com/videos/student-loan"));
        en.put("pm-kisan", scheme("PM-Kisan Samman Nidhi", List.of("Aadhaar Card", "Land ownership papers (Khasra/Khatauni)", "Active bank account details", "Citizenship proof"), "Register online on the PM-Kisan portal or submit land records at the local Agriculture Revenue Department.", "https://pmkisan.gov.in"));
        en.put("pm-mudra", scheme("PM MUDRA Business Loan", List.of("Identity proof (PAN/Aadhaar)", "Proof of business address", "Projected business proposal", "6 months bank account statement"), "Submit your business proposal and KYC documents to any commercial or rural bank for loans up to ₹10 Lakhs.", "https://mudra.org.in"));
        en.put("pm-awas", scheme("PM Awas Yojana (PMAY)", List.of("Aadhaar Card", "Income proof certificate", "Proof of current residence", "Affidavit of non-ownership of pucca house"), "Apply through your housing finance institution or Common Service Centre (CSC) for credit-linked subsidy up to ₹2.67 Lakhs.", "https://pmaymis.gov.in"));
        en.put("sukanya-samriddhi", scheme("Sukanya Samriddhi Yojana (SSY)", List.of("Girl Child Birth Certificate", "Parent/Guardian ID proof", "Address proof", "Medical/Hospital birth record"), "Open an SSY account at any authorized bank or post office with an initial deposit of ₹250 for daughters below 10 years.", "https://example.com/ssy"));
        en.put("atal-pension", scheme("Atal Pension Yojana (APY)", List.of("Aadhaar Card", "Savings Bank Account details", "Nominee identification", "Mobile number linked to bank"), "Enroll via your mobile banking portal or visit your bank branch to set up automatic monthly retirement contributions.", "https://npscra.nsdl.co.in"));
        en.put("ayushman-bharat", scheme("Ayushman Bharat (PM-JAY)", List.of("Ration Card / SECC Data proof", "Aadhaar Card", "Family identity certificate", "Active contact number"), "Verify eligibility at any empaneled hospital helpdesk to obtain your Ayushman Golden Card for ₹5 Lakh health cover.", "https://pmjay.gov.in"));
        en.put("stand-up-india", scheme("Stand-Up India Scheme", List.of("Identity proof", "SC/ST certificate (if applicable)", "Greenfield business plan", "Pollution board clearances"), "Apply online on the Stand-Up India portal for composite loans between ₹10 Lakh and ₹1 Crore for greenfield projects.", "https://standupmitra.in"));
        en.put("pm-svanidhi", scheme("PM SVANidhi Micro Credit", List.of("Vending Certificate / Urban Local Body ID", "Aadhaar Card", "Bank account details", "Letter of Recommendation (ULB)"), "Apply online on PM SVANidhi portal to receive working capital micro-loans of ₹10,000 to ₹50,000 with 7% interest subsidy.", "https://pmsvanidhi.mohua.gov.in"));
        langMap.put("en", en);

        // 2. Hindi
        Map<String, Map<String, Object>> hi = new LinkedHashMap<>();
        hi.put("senior-citizen", scheme("वरिष्ठ नागरिक कल्याण योजना", List.of("सरकारी पहचान पत्र", "आयु प्रमाण पत्र", "आवास प्रमाण पत्र", "हालिया पासपोर्ट फोटो"), "निकटतम अधिकृत बैंक सेवा केंद्र के माध्यम से अपनी पहचान, आयु और पते के दस्तावेज जमा करें।", "https://example.com/videos/senior-citizen"));
        hi.put("student-loan", scheme("छात्र शिक्षा ऋण सहायता योजना", List.of("सरकारी पहचान पत्र", "कॉलेज प्रवेश पत्र", "शैक्षणिक अंकतालिकाएं", "आय प्रमाण पत्र"), "अपने प्रवेश और वित्तीय दस्तावेजों के साथ शिक्षा ऋण आवेदन भरें और नजदीकी बैंक शाखा में जमा करें।", "https://example.com/videos/student-loan"));
        hi.put("pm-kisan", scheme("पीएम-किसान सम्मान निधि योजना", List.of("आधार कार्ड", "भूमि स्वामित्व दस्तावेज (खसरा/खतौनी)", "सक्रिय बैंक खाता विवरण", "नागरिकता प्रमाण"), "पीएम-किसान पोर्टल पर ऑनलाइन पंजीकरण करें या स्थानीय कृषि विभाग में भूमि रिकॉर्ड जमा करें।", "https://pmkisan.gov.in"));
        hi.put("pm-mudra", scheme("प्रधानमंत्री मुद्रा व्यापार ऋण", List.of("पहचान प्रमाण (पैन/आधार)", "व्यवसाय पता प्रमाण", "परियोजना व्यापार प्रस्ताव", "6 महीने का बैंक विवरण"), "₹10 लाख तक के ऋण के लिए किसी भी वाणिज्यिक या ग्रामीण बैंक में अपना व्यवसाय प्रस्ताव और केवाईसी दस्तावेज जमा करें।", "https://mudra.org.in"));
        hi.put("pm-awas", scheme("प्रधानमंत्री आवास योजना (PMAY)", List.of("आधार कार्ड", "आय प्रमाण पत्र", "वर्तमान निवास प्रमाण", "पक्के मकान न होने का शपथ पत्र"), "₹2.67 लाख तक की ब्याज सब्सिडी के लिए अपने बैंक या सीएससी केंद्र के माध्यम से आवेदन करें।", "https://pmaymis.gov.in"));
        hi.put("sukanya-samriddhi", scheme("सुकन्या समृद्धि योजना (SSY)", List.of("बालिका का जन्म प्रमाण पत्र", "अभिभावक का पहचान पत्र", "आवासीय पता प्रमाण", "पासपोर्ट फोटो"), "10 वर्ष से कम आयु की बेटियों के लिए ₹250 की न्यूनतम जमा राशि के साथ किसी भी बैंक या डाकघर में खाता खोलें।", "https://example.com/ssy"));
        hi.put("atal-pension", scheme("अटल पेंशन योजना (APY)", List.of("आधार कार्ड", "बचत बैंक खाता विवरण", "नॉमिनी पहचान पत्र", "बैंक से लिंक मोबाइल नंबर"), "60 वर्ष की आयु के बाद ₹1,000 से ₹5,000 तक की मासिक पेंशन के लिए अपनी बैंक शाखा से आवेदन करें।", "https://npscra.nsdl.co.in"));
        hi.put("ayushman-bharat", scheme("आयुष्मान भारत (PM-JAY)", List.of("राशन कार्ड / SECC डेटा प्रमाण", "आधार कार्ड", "पारिवारिक पहचान पत्र", "सक्रिय मोबाइल नंबर"), "प्रति परिवार प्रति वर्ष ₹5 लाख तक के मुफ्त इलाज के लिए सूचीबद्ध अस्पताल में अपना गोल्डन कार्ड बनवाएं।", "https://pmjay.gov.in"));
        hi.put("stand-up-india", scheme("स्टैंड-अप इंडिया उद्यम योजना", List.of("पहचान प्रमाण", "एससी/एसटी प्रमाण पत्र (यदि लागू हो)", "व्यापार योजना", "पैन कार्ड"), "महिला और एससी/एसटी उद्यमियों के लिए ₹10 लाख से ₹1 करोड़ तक के बैंक ऋण हेतु ऑनलाइन आवेदन करें।", "https://standupmitra.in"));
        hi.put("pm-svanidhi", scheme("पीएम स्वनिधि सूक्ष्म ऋण योजना", List.of("वेंडिंग प्रमाण पत्र / निकाय पहचान पत्र", "आधार कार्ड", "बैंक खाता विवरण", "सिफारिश पत्र"), "स्ट्रीट वेंडर्स 7% ब्याज सब्सिडी के साथ ₹10,000 से ₹50,000 तक का कार्यशील पूंजी ऋण प्राप्त करें।", "https://pmsvanidhi.mohua.gov.in"));
        langMap.put("hi", hi);

        // 3. Kannada
        Map<String, Map<String, Object>> kn = new LinkedHashMap<>();
        kn.put("senior-citizen", scheme("ಹಿರಿಯ ನಾಗರಿಕರ ಕಲ್ಯಾಣ ಯೋಜನೆ", List.of("ಸರ್ಕಾರಿ ಗುರುತಿನ ಚೀಟಿ", "ವಯಸ್ಸಿನ ಪುರಾವೆ", "ವಿಳಾಸದ ದಾಖಲೆ", "ಇತ್ತೀಚಿನ ಭಾವಚಿತ್ರ"), "ನಿಮ್ಮ ಗುರುತು, ವಯಸ್ಸು ಮತ್ತು ವಿಳಾಸದ ದಾಖಲೆಗಳನ್ನು ಹತ್ತಿರದ ಅಧಿಕೃತ ಸೇವಾ ಕೇಂದ್ರದ ಮೂಲಕ ಸಲ್ಲಿಸಿ.", "https://example.com/videos/senior-citizen"));
        kn.put("student-loan", scheme("ವಿದ್ಯಾರ್ಥಿ ಶೈಕ್ಷಣಿಕ ಸಾಲ ನೆರವು ಯೋಜನೆ", List.of("ಸರ್ಕಾರಿ ಗುರುತಿನ ಚೀಟಿ", "ಪ್ರವೇಶ ಪತ್ರ (Admission Letter)", "ಅಂಕಪಟ್ಟಿಗಳು", "ಆದಾಯ ಪ್ರಮಾಣಪತ್ರ"), "ನಿಮ್ಮ ಪ್ರವೇಶ ಮತ್ತು ಆರ್ಥಿಕ ದಾಖಲೆಗಳೊಂದಿಗೆ ಶೈಕ್ಷಣಿಕ ಸಾಲದ ಅರ್ಜಿಯನ್ನು ಪೂರ್ಣಗೊಳಿಸಿ ಬ್ಯಾಂಕಿಗೆ ಸಲ್ಲಿಸಿ.", "https://example.com/videos/student-loan"));
        kn.put("pm-kisan", scheme("ಪಿಎಂ ಕಿಸಾನ್ ಸಮ್ಮಾನ್ ನಿಧಿ", List.of("ಆಧಾರ್ ಕಾರ್ಡ್", "ಜಮೀನಿನ ಪಹಣಿ (RTC/ಖಾತೆ)", "ಬ್ಯಾಂಕ್ ಖಾತೆ ವಿವರ", "ಮೊಬೈಲ್ ಸಂಖ್ಯೆ"), "ವರ್ಷಕ್ಕೆ ₹6,000 ನೇರ ಆರ್ಥಿಕ ನೆರವು ಪಡೆಯಲು ಕಂದಾಯ ಇಲಾಖೆ ಅಥವಾ ಪಿಎಂ-ಕಿಸಾನ್ ಪೋರ್ಟಲ್‌ನಲ್ಲಿ ನೋಂದಾಯಿಸಿ.", "https://pmkisan.gov.in"));
        kn.put("pm-mudra", scheme("ಪ್ರಧಾನಮಂತ್ರಿ ಮುದ್ರಾ ಉದ್ಯಮ ಸಾಲ", List.of("ಪ್ಯಾನ್/ಆಧಾರ್ ಕಾರ್ಡ್", "ಉದ್ಯಮದ ವಿಳಾಸ ಪುರಾವೆ", "ಉದ್ಯಮ ಯೋಜನೆ ಪ್ರಸ್ತಾವನೆ", "6 ತಿಂಗಳ ಬ್ಯಾಂಕ್ ಸ್ಟೇಟ್‌ಮೆಂಟ್"), "ಸಣ್ಣ ಮತ್ತು ಮಧ್ಯಮ ಉದ್ಯಮ ಸ್ಥಾಪನೆಗಾಗಿ ₹10 ಲಕ್ಷದವರೆಗೆ ಜಾಮೀನು ರಹಿತ ಸಾಲ ಪಡೆಯಿರಿ.", "https://mudra.org.in"));
        kn.put("pm-awas", scheme("ಪ್ರಧಾನಮಂತ್ರಿ ಆವಾಸ್ ವಸತಿ ಯೋಜನೆ", List.of("ಆಧಾರ್ ಕಾರ್ಡ್", "ಆದಾಯ ಪ್ರಮಾಣಪತ್ರ", "ಹಾಲಿ ವಾಸಸ್ಥಳದ ದಾಖಲೆ", "ಸ್ವಂತ ಮನೆ ಇಲ್ಲದಿರುವ ಪ್ರಮಾಣಪತ್ರ"), "ಕೈಗೆಟುಕುವ ಸ್ವಂತ ಮನೆ ನಿರ್ಮಾಣಕ್ಕಾಗಿ ಗೃಹ ಸಾಲದ ಮೇಲೆ ₹2.67 ಲಕ್ಷದವರೆಗೆ ಬಡ್ಡಿ ಸಬ್ಸಿಡಿ ಪಡೆಯಿರಿ.", "https://pmaymis.gov.in"));
        kn.put("sukanya-samriddhi", scheme("ಸುಕನ್ಯಾ ಸಮೃದ್ಧಿ ಯೋಜನೆ (SSY)", List.of("ಹೆಣ್ಣು ಮಗುವಿನ ಜನನ ಪ್ರಮಾಣಪತ್ರ", "ಪೋಷಕರ ಗುರುತಿನ ಚೀಟಿ", "ವಿಳಾಸದ ದಾಖಲೆ", "ಭಾವಚಿತ್ರ"), "10 ವರ್ಷದೊಳಗಿನ ಹೆಣ್ಣುಮಕ್ಕಳಿಗೆ ₹250 ಆರಂಭಿಕ ಠೇವಣಿಯೊಂದಿಗೆ 8.2% ಹೆಚ್ಚಿನ ಬಡ್ಡಿದರದ ಉಳಿತಾಯ ಖಾತೆ ತೆರೆಯಿರಿ.", "https://example.com/ssy"));
        kn.put("atal-pension", scheme("ಅಟಲ್ ಪಿಂಚಣಿ ಯೋಜನೆ (APY)", List.of("ಆಧಾರ್ ಕಾರ್ಡ್", "ಉಳಿತಾಯ ಬ್ಯಾಂಕ್ ಖಾತೆ ವಿವರ", "ನಾಮಿನಿ ಗುರುತಿನ ಚೀಟಿ", "ಲಿಂಕ್ ಆದ ಮೊಬೈಲ್ ಸಂಖ್ಯೆ"), "60 ವರ್ಷಗಳ ನಂತರ ತಿಂಗಳಿಗೆ ₹1,000 ದಿಂದ ₹5,000 ವರೆಗೆ ಖಚಿತ ಪಿಂಚಣಿ ಪಡೆಯಲು ನೋಂದಾಯಿಸಿ.", "https://npscra.nsdl.co.in"));
        kn.put("ayushman-bharat", scheme("ಆಯುಷ್ಮಾನ್ ಭಾರತ್ ಆರೋಗ್ಯ ಯೋಜನೆ", List.of("ರೇಷನ್ ಕಾರ್ಡ್ / SECC ದಾಖಲೆ", "ಆಧಾರ್ ಕಾರ್ಡ್", "ಕುಟುಂಬದ ಗುರುತಿನ ಚೀಟಿ", "ಮೊಬೈಲ್ ಸಂಖ್ಯೆ"), "ವರ್ಷಕ್ಕೆ ₹5 ಲಕ್ಷದವರೆಗೆ ನಗದು ರಹಿತ ಉಚಿತ ಆಸ್ಪತ್ರೆ ಚಿಕಿತ್ಸೆಗಾಗಿ ಆಯುಷ್ಮಾನ್ ಕಾರ್ಡ್ ಪಡೆದುಕೊಳ್ಳಿ.", "https://pmjay.gov.in"));
        kn.put("stand-up-india", scheme("ಸ್ಟ್ಯಾಂಡ್-ಅಪ್ ಇಂಡಿಯಾ ಯೋಜನೆ", List.of("ಗುರುತಿನ ಚೀಟಿ", "SC/ST ಪ್ರಮಾಣಪತ್ರ (ಅನ್ವಯಿಸಿದರೆ)", "ಹೊಸ ಉದ್ಯಮ ಯೋಜನೆ", "ಪ್ಯಾನ್ ಕಾರ್ಡ್"), "ಮಹಿಳಾ ಮತ್ತು SC/ST ಉದ್ಯಮಿಗಳಿಗಾಗಿ ₹10 ಲಕ್ಷದಿಂದ ₹1 ಕೋಟಿಯವರೆಗೆ ಸಾಲ ಸೌಲಭ್ಯ.", "https://standupmitra.in"));
        kn.put("pm-svanidhi", scheme("ಪಿಎಂ ಸ್ವನಿಧಿ ಕಿರು ಸಾಲ ಯೋಜನೆ", List.of("ವ್ಯಾಪಾರಿ ಗುರುತಿನ ಚೀಟಿ", "ಆಧಾರ್ ಕಾರ್ಡ್", "ಬ್ಯಾಂಕ್ ಖಾತೆ ವಿವರ", "ಸ್ಥಳೀಯ ಸಂಸ್ಥೆಯ ಶಿಫಾರಸು ಪತ್ರ"), "ಬೀದಿ ಬದಿ ವ್ಯಾಪಾರಿಗಳಿಗೆ ಶೇ. 7 ರಷ್ಟು ಬಡ್ಡಿ ಸಬ್ಸಿಡಿಯೊಂದಿಗೆ ₹10,000 ದಿಂದ ₹50,000 ವರೆಗೆ ಸಾಲ ಸೌಲಭ್ಯ.", "https://pmsvanidhi.mohua.gov.in"));
        langMap.put("kn", kn);

        // 4. Telugu
        Map<String, Map<String, Object>> te = new LinkedHashMap<>();
        te.put("senior-citizen", scheme("సీనియర్ సిటిజన్ సంక్షేమ పథకం", List.of("ప్రభుత్వ గుర్తింపు పత్రం", "వయస్సు ధృవీకరణ", "చిరునామా రుజువు", "ఇటీవలి పాస్‌పోర్ట్ ఫోటో"), "సమీపంలోని అధీకృత సేవా కేంద్రం ద్వారా మీ గుర్తింపు, వయస్సు మరియు చిరునామా పత్రాలను సమర్పించండి.", "https://example.com/videos/senior-citizen"));
        te.put("student-loan", scheme("విద్యార్థి విద్యా రుణ సహాయ పథకం", List.of("ప్రభుత్వ గుర్తింపు పత్రం", "ప్రవేశ ధ్రువీకరణ పత్రం", "మార్కుల జాబితా", "ఆదాయ ధృవీకరణ పత్రం"), "మీ అడ్మిషన్ మరియు ఆర్థిక పత్రాలతో విద్యార్థి రుణ దరఖాస్తును పూర్తి చేసి సమీప బ్యాంకులో సమర్పించండి.", "https://example.com/videos/student-loan"));
        te.put("pm-kisan", scheme("పీఎం కిసాన్ సమ్మాన్ నిధి", List.of("ఆధార్ కార్డు", "భూమి యాజమాన్య పత్రాలు (పట్టాదారు పాస్‌బుక్)", "బ్యాంక్ ఖాతా వివరాలు", "మొబైల్ నంబర్"), "ఏడాదికి ₹6,000 ఆర్థిక సహాయం కోసం రెవెన్యూ విభాగం లేదా పోర్టల్‌లో నమోదు చేసుకోండి.", "https://pmkisan.gov.in"));
        te.put("pm-mudra", scheme("పీఎం ముద్రా వ్యాపార రుణం", List.of("పాన్/ఆధార్ కార్డు", "వ్యాపార చిరునామా రుజువు", "వ్యాపార ప్రణాళిక", "6 నెలల బ్యాంక్ స్టేట్‌మెంట్"), "చిన్న వ్యాపారాల కోసం ₹10 లక్షల వరకు పూచీకత్తు లేని ముద్రా రుణాలను పొందండి.", "https://mudra.org.in"));
        te.put("pm-awas", scheme("పీఎం ఆవాస్ గృహ నిర్మాణ పథకం", List.of("ఆధార్ కార్డు", "ఆదాయ ధృవీకరణ పత్రం", "నివాస రుజువు", "సొంత ఇల్లు లేదని అఫిడవిట్"), "సొంత ఇంటి నిర్మాణానికి లేదా కొనుగోలుకు గృహ రుణాలపై ₹2.67 లక్షల వరకు వడ్డీ రాయితీని పొందండి.", "https://pmaymis.gov.in"));
        te.put("sukanya-samriddhi", scheme("సుకన్య సమృద్ధి యోజన (SSY)", List.of("ఆడపిల్ల పుట్టిన తేదీ ధృవీకరణ పత్రం", "తల్లిదండ్రుల గుర్తింపు కార్డు", "చిరునామా రుజువు", "ఫోటో"), "10 ఏళ్లలోపు బాలికల కోసం 8.2% వడ్డీతో ₹250 కనీస డిపాజిట్‌తో బ్యాంకులో ఖాతా తెరవండి.", "https://example.com/ssy"));
        te.put("atal-pension", scheme("అటల్ పెన్షన్ యోజన (APY)", List.of("ఆధార్ కార్డు", "పొదుపు బ్యాంక్ ఖాతా వివరాలు", "నామినీ పత్రం", "లింక్ చేయబడిన మొబైల్"), "60 ఏళ్ల తర్వాత నెలకు ₹1,000 నుండి ₹5,000 వరకు స్థిర పెన్షన్ పొందడానికి నమోదు చేసుకోండి.", "https://npscra.nsdl.co.in"));
        te.put("ayushman-bharat", scheme("ఆయుష్మాన్ భారత్ ఆరోగ్య రక్షణ", List.of("రేషన్ కార్డు / SECC వివరాలు", "ఆధార్ కార్డు", "కుటుంబ గుర్తింపు పత్రం", "మొబైల్ నంబర్"), "సంవత్సరానికి ₹5 లక్షల వరకు ఉచిత హాస్పిటల్ చికిత్స కోసం ఆయుష్మాన్ గోల్డెన్ కార్డును పొందండి.", "https://pmjay.gov.in"));
        te.put("stand-up-india", scheme("స్టాండ్-అప్ ఇండియా పథకం", List.of("గుర్తింపు కార్డు", "SC/ST సర్టిఫికేట్ (వర్తిస్తే)", "కొత్త ప్రాజెక్ట్ నివేదిక", "పాన్ కార్డు"), "మహిళా మరియు SC/ST పారిశ్రామికవేత్తలకు ₹10 లక్షల నుండి ₹1 కోటి వరకు రుణ సదుపాయం.", "https://standupmitra.in"));
        te.put("pm-svanidhi", scheme("పీఎం స్వనిధి మైక్రో లోన్ పథకం", List.of("వెండింగ్ సర్టిఫికేట్ / ఐడీ కార్డు", "ఆధార్ కార్డు", "బ్యాంక్ ఖాతా వివరాలు", "సిఫార్సు లేఖ"), "వీధి వ్యాపారులకు 7% వడ్డీ రాయితీతో ₹10,000 నుండి ₹50,000 వరకు వ్యాపార రుణాలు లభిస్తాయి.", "https://pmsvanidhi.mohua.gov.in"));
        langMap.put("te", te);

        // 5. Tamil
        Map<String, Map<String, Object>> ta = new LinkedHashMap<>();
        ta.put("senior-citizen", scheme("மூத்த குடிமக்கள் நலத் திட்டம்", List.of("அரசு அடையாள அட்டை", "வயதுச் சான்றிதழ்", "முகவரிச் சான்று", "சமீபத்திய புகைப்படம்"), "அருகிலுள்ள அங்கீகரிக்கப்பட்ட சேவை மையம் மூலம் உங்கள் அடையாளம், வயது மற்றும் முகவரி ஆவணங்களை சமர்ப்பிக்கவும்.", "https://example.com/videos/senior-citizen"));
        ta.put("student-loan", scheme("மாணவர் கல்விக் கடன் உதவித் திட்டம்", List.of("அரசு அடையாள அட்டை", "சேர்க்கை கடிதம் (Admission Letter)", "மதிப்பெண் சான்றிதழ்கள்", "வருமானச் சான்றிதழ்"), "உங்கள் கல்வி சேர்க்கை மற்றும் வருமான ஆவணங்களுடன் கடன் விண்ணப்பத்தை பூர்த்தி செய்து அருகிலுள்ள வங்கியில் சமர்ப்பிக்கவும்.", "https://example.com/videos/student-loan"));
        ta.put("pm-kisan", scheme("பிஎம் கிசான் விவசாய நலத் திட்டம்", List.of("ஆதார் அட்டை", "நில உரிமை ஆவணங்கள் (பட்டா/சிட்டா)", "வங்கி கணக்கு விவரங்கள்", "மொபைல் எண்"), "ஆண்டுக்கு ரூ. 6,000 நேரடி நிதி உதவி பெற போர்ட்டலில் அல்லது வேளாண்மை துறையில் பதிவு செய்யவும்.", "https://pmkisan.gov.in"));
        ta.put("pm-mudra", scheme("பிரதான் மந்திரி முத்ரா தொழில் கடன்", List.of("பான் / ஆதார் அட்டை", "தொழில் முகவரி சான்று", "தொழில் திட்ட அறிக்கை", "6 மாத வங்கி கணக்கு அறிக்கை"), "சிறு தொழில்களுக்கு ரூ. 10 லட்சம் வரை பிணையமற்ற முத்ரா கடன்களை வங்கிகள் மூலம் பெறலாம்.", "https://mudra.org.in"));
        ta.put("pm-awas", scheme("பிரதான் மந்திரி ஆவாஸ் வீட்டு வசதி திட்டம்", List.of("ஆதார் அட்டை", "வருமானச் சான்றிதழ்", "இருப்பிடச் சான்று", "சொந்த வீடு இல்லை என்பதற்கான சான்று"), "சொந்த வீடு கட்ட அல்லது வாங்க வீட்டுக் கடன்களுக்கு ரூ. 2.67 லட்சம் வரை வட்டி மானியம் பெறலாம்.", "https://pmaymis.gov.in"));
        ta.put("sukanya-samriddhi", scheme("சுகன்யா சம்ரித்தி பெண் குழந்தை சேமிப்பு", List.of("பெண் குழந்தையின் பிறப்புச் சான்றிதழ்", "பெற்றோர் அடையாள அட்டை", "முகவரிச் சான்று", "புகைப்படம்"), "10 வயதுக்குட்பட்ட பெண் குழந்தைகளுக்கு 8.2% உயர் வட்டியில் ரூ. 250 குறைந்தபட்ச வைப்புத்தொகையுடன் கணக்கு தொடங்கவும்.", "https://example.com/ssy"));
        ta.put("atal-pension", scheme("அடல் ஓய்வூதியத் திட்டம் (APY)", List.of("ஆதார் அட்டை", "சேமிப்பு வங்கி கணக்கு விவரங்கள்", "வாரிசுதாரர் ஆவணம்", "மொபைல் எண்"), "60 வயதிற்குப் பிறகு மாதம் ரூ. 1,000 முதல் ரூ. 5,000 வரை உத்தரவாதமான ஓய்வூதியம் பெற பதிவு செய்யவும்.", "https://npscra.nsdl.co.in"));
        ta.put("ayushman-bharat", scheme("ஆயுஷ்மான் பாரத் மருத்துவ காப்பீடு", List.of("ரேஷன் அட்டை / SECC சான்று", "ஆதார் அட்டை", "குடும்ப அடையாள அட்டை", "மொபைல் எண்"), "ஆண்டுக்கு ரூ. 5 லட்சம் வரை இலவச மருத்துவ சிகிச்சை பெற ஆயுஷ்மான் தங்க அட்டையைப் பெறுங்கள்.", "https://pmjay.gov.in"));
        ta.put("stand-up-india", scheme("ஸ்டாண்ட்-அப் இந்தியா திட்டம்", List.of("அடையாள அட்டை", "SC/ST சான்றிதழ் (பொருந்தினால்)", "தொழில் திட்ட அறிக்கை", "பான் அட்டை"), "மகளிர் மற்றும் SC/ST தொழில் முனைவோருக்கு ரூ. 10 லட்சம் முதல் ரூ. 1 கோடி வரை கடன் உதவி.", "https://standupmitra.in"));
        ta.put("pm-svanidhi", scheme("பிஎம் ஸ்வாநிதி சிறு கடன் திட்டம்", List.of("வியாபார அடையாள அட்டை", "ஆதார் அட்டை", "வங்கி கணக்கு விவரங்கள்", "பரிந்துரை கடிதம்"), "தெருவோர வியாபாரிகளுக்கு 7% வட்டி மானியத்துடன் ரூ. 10,000 முதல் ரூ. 50,000 வரை மூலதனக் கடன்.", "https://pmsvanidhi.mohua.gov.in"));
        langMap.put("ta", ta);

        return langMap;
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

package com.venicetranslate.manga;
import android.text.TextUtils;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
final class VeniceClient {
    static final class Result {
        final Map<Integer, String> translations;
        final int promptTokens, completionTokens;
        Result(Map<Integer, String> translations, int promptTokens, int completionTokens) {
            this.translations = translations;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
        }
    }
    private VeniceClient() {}
    static Result translate(String apiKey, String model, List<Dialogue> items, String previous)
            throws Exception {
        JSONObject data = new JSONObject();
        data.put("model", model);
        data.put("stream", false);
        data.put("temperature", 0.2);
        data.put("max_completion_tokens", 1500);
        String system = "Ты профессиональный переводчик англоязычных вебтунов на русский язык. " +
                "Переводи реплики ЕСТЕСТВЕННО, как в качественной русской локализации манги: " +
                "сохраняй характер персонажей, эмоции, шутки, разговорную речь, обращения и ругательства " +
                "без смягчения, эвфемизмов и цензуры. Учитывай общий контекст и порядок реплик. " +
                "Не придумывай сюжет, не добавляй пояснений, не меняй имён собственных. " +
                "Если одна реплика является фрагментом фразы, переведи естественно и кратко. " +
                "Никаких замечаний и предисловий. " +
                "Верни только JSON-объект: ключи — идентификаторы реплик, значения — строки русского перевода. " +
                "Не пропускай ключи. Для коротких звуковых эффектов дай естественный короткий аналог.";
        JSONObject blocks = new JSONObject();
        for (Dialogue d : items) blocks.put(String.valueOf(d.id), d.english);
        String user = "Переведи английский текст вебтуна на русский. Реплики представлены в порядке чтения сверху вниз. " +
                "Это задача перевода, а не продолжения истории.\n" +
                (previous.isEmpty() ? "" : "Контекст предыдущего экрана (для согласованности, повторять не надо):\n" + previous + "\n") +
                "Английские реплики:\n" + blocks.toString() +
                "\nВерни JSON {\"0\":\"русский перевод\", ...} с теми же ключами.";
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", system));
        messages.put(new JSONObject().put("role", "user").put("content", user));
        data.put("messages", messages);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("https://api.venice.ai/api/v1/chat/completions").openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            byte[] payload = data.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) { os.write(payload); }
            int code = conn.getResponseCode();
            String body;
            try (InputStream stream = code < 400 ? conn.getInputStream() : conn.getErrorStream()) {
                body = readUtf8(stream);
            }
            if (code >= 400) throw new Exception(localizeError(code, body));
            JSONObject response = new JSONObject(body);
            String raw = response.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content", "").trim();
            if (TextUtils.isEmpty(raw)) throw new Exception("Venice вернул пустой перевод");
            int a = raw.indexOf('{'), z = raw.lastIndexOf('}');
            if (a == -1 || z <= a) throw new JSONException("В ответе нет JSON-объекта");
            JSONObject translated = new JSONObject(raw.substring(a, z + 1));
            Map<Integer, String> result = new HashMap<>();
            for (Dialogue d : items) {
                String value = translated.optString(String.valueOf(d.id), "").trim();
                if (!value.isEmpty()) result.put(d.id, value);
            }
            if (result.isEmpty()) throw new Exception("Venice не выдал перевод ни одной реплики");
            JSONObject usage = response.optJSONObject("usage");
            return new Result(result,
                    usage == null ? 0 : usage.optInt("prompt_tokens", 0),
                    usage == null ? 0 : usage.optInt("completion_tokens", 0));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
    private static String readUtf8(InputStream stream) throws Exception {
        if (stream == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            if (out.size() > 1_000_000) throw new Exception("Ответ сервера слишком большой");
            out.write(buffer, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }
    private static String localizeError(int code, String response) {
        switch (code) {
            case 401: return "Venice 401: неверный либо отозванный API-ключ";
            case 403: return "Venice 403: у ключа нет доступа к этой модели";
            case 402: return "Venice 402: недостаточно средств на API-балансе";
            case 404: return "Venice 404: модель или адрес API не найден";
            case 429: return "Venice 429: слишком частые запросы, нужна пауза";
            default:
                if (code >= 500) return "Venice " + code + ": временная ошибка сервера";
                try {
                    JSONObject j = new JSONObject(response);
                    JSONObject e = j.optJSONObject("error");
                    if (e != null) return "Venice " + code + ": " + e.optString("message", "ошибка API");
                } catch (Exception ignored) {}
                return "Venice HTTP " + code + ": проверь модель и настройки";
        }
    }
}

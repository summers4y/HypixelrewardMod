package com.hypixel.reward;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.cookie.BasicClientCookie;
import org.apache.http.util.EntityUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HypRewardEventHandler {

    private static final Pattern REWARD_URL_PATTERN =
            Pattern.compile("https?://rewards\\.hypixel\\.net/claim-reward/[a-zA-Z0-9-]+");

    // Claim session state
    static String claimRewardCode;
    static List<String> claimOptions = new ArrayList<>();
    static List<String> claimOptionDescs = new ArrayList<>();
    static List<String> claimOptionRarities = new ArrayList<>();
    static int claimSelectedIndex = -1;
    static String claimCsrfToken;
    static String claimId;
    static int claimActiveAd;
    static Map<String, String> claimCookies = new HashMap<>();

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        String msgText = event.message.getUnformattedText();
        Matcher matcher = REWARD_URL_PATTERN.matcher(msgText);
        if (!matcher.find()) {
            return;
        }

        String rewardUrl = matcher.group();
        String rewardCode = rewardUrl.substring(rewardUrl.lastIndexOf('/') + 1);

        // Remove the URL from the original message
        String cleaned = msgText.replace(rewardUrl, "").trim();
        if (cleaned.isEmpty()) {
            event.setCanceled(true);
        } else {
            event.message = new ChatComponentText(cleaned);
        }

        // Fetch reward data in background
        new Thread(() -> fetchAndShowRewards(rewardUrl, rewardCode)).start();
    }

    private void fetchAndShowRewards(String url, String rewardCode) {
        try {
            BasicCookieStore cookieStore = new BasicCookieStore();
            CloseableHttpClient httpClient = HttpClients.custom()
                    .setDefaultCookieStore(cookieStore)
                    .build();

            // GET the reward page
            HttpGet httpGet = new HttpGet(url);
            httpGet.setHeader("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

            CloseableHttpResponse response = httpClient.execute(httpGet);
            int statusCode = response.getStatusLine().getStatusCode();

            String pageContent;
            if (statusCode >= 300 && statusCode < 400 && response.containsHeader("Location")) {
                // Follow redirect to capture cookies from both hops
                String location = response.getFirstHeader("Location").getValue();
                EntityUtils.consume(response.getEntity());
                response.close();

                if (!location.startsWith("http")) {
                    location = "https://rewards.hypixel.net" + location;
                }
                HttpGet redirectGet = new HttpGet(location);
                redirectGet.setHeader("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
                CloseableHttpResponse redirectResp = httpClient.execute(redirectGet);
                pageContent = EntityUtils.toString(redirectResp.getEntity(), "UTF-8");
                redirectResp.close();
            } else {
                // Read response directly
                pageContent = EntityUtils.toString(response.getEntity(), "UTF-8");
                response.close();
            }

            // Extract window.appData
            String appDataJson = extractAppData(pageContent);
            if (appDataJson == null) {
                sendChat(EnumChatFormatting.RED + "[HypReward] 未能找到奖励数据 (appData)");
                httpClient.close();
                return;
            }

            // Parse JSON
            JsonParser parser = new JsonParser();
            JsonObject appData = parser.parse(appDataJson).getAsJsonObject();
            JsonArray rewards = appData.getAsJsonArray("rewards");
            if (rewards == null || rewards.size() == 0) {
                sendChat(EnumChatFormatting.RED + "[HypReward] 没有可领取的奖励");
                httpClient.close();
                return;
            }

            // Build reward options
            List<String> options = new ArrayList<>();
            List<String> descs = new ArrayList<>();
            List<String> rarities = new ArrayList<>();
            for (JsonElement elem : rewards) {
                JsonObject r = elem.getAsJsonObject();
                StringBuilder desc = new StringBuilder();

                String rarity = getStringOrDefault(r, "rarity", "");
                String gameType = getStringOrDefault(r, "gameType", "");
                String rewardType = getStringOrDefault(r, "reward", "");
                String amount = getStringOrDefault(r, "amount", "");
                String pkg = getStringOrDefault(r, "package", "");

                if (!rarity.isEmpty()) desc.append(rarity).append(" ");
                if (!pkg.isEmpty()) {
                    desc.append(pkg);
                } else {
                    if (!gameType.isEmpty() && !rewardType.isEmpty()) {
                        desc.append(gameType).append(" ").append(rewardType);
                    } else if (!rewardType.isEmpty()) {
                        desc.append(rewardType);
                    }
                }
                if (!amount.isEmpty()) desc.append(" x").append(amount);

                String display = desc.toString().trim();
                if (display.isEmpty()) display = "未知奖励";
                options.add(display);
                descs.add(display);
                rarities.add(rarity.toLowerCase());
            }

            // Extract CSRF token
            String csrfToken = null;
            for (org.apache.http.cookie.Cookie c : cookieStore.getCookies()) {
                if ("_csrf".equals(c.getName()) && ".hypixel.net".equals(c.getDomain())) {
                    csrfToken = c.getValue();
                    break;
                }
            }
            if (csrfToken == null) {
                csrfToken = extractSecurityToken(pageContent);
            }

            // Extract id and activeAd
            String id = appData.has("id") ? appData.get("id").getAsString() : rewardCode;
            int activeAd = appData.has("activeAd") ? appData.get("activeAd").getAsInt() : 1;

            // Save cookies
            Map<String, String> cookies = new HashMap<>();
            for (org.apache.http.cookie.Cookie c : cookieStore.getCookies()) {
                cookies.put(c.getName(), c.getValue());
            }

            httpClient.close();

            // Store claim session
            claimRewardCode = rewardCode;
            claimOptions = options;
            claimOptionDescs = descs;
            claimOptionRarities = rarities;
            claimSelectedIndex = -1;
            claimCsrfToken = csrfToken;
            claimId = id;
            claimActiveAd = activeAd;
            claimCookies = cookies;

            // Show options in chat (on main thread)
            Minecraft.getMinecraft().addScheduledTask(() -> showRewardOptions(options));

        } catch (Exception e) {
            sendChat(EnumChatFormatting.RED + "[HypReward] 获取奖励数据失败: " + e.getMessage());
        }
    }

    static void showRewardOptions(List<String> options) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.thePlayer.addChatMessage(new ChatComponentText(
                EnumChatFormatting.GOLD + "========== Hypixel 每日奖励 =========="));
        for (int i = 0; i < options.size(); i++) {
            EnumChatFormatting color = getRarityColor(
                    i < claimOptionRarities.size() ? claimOptionRarities.get(i) : "");
            IChatComponent optionText = new ChatComponentText(
                    color + "  [" + (i + 1) + "] " + options.get(i));
            optionText.setChatStyle(new ChatStyle()
                    .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/hypclaim select " + i))
                    .setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            new ChatComponentText(EnumChatFormatting.YELLOW + "点击选择此奖励")))
                    .setColor(color));
            mc.thePlayer.addChatMessage(optionText);
        }
        mc.thePlayer.addChatMessage(new ChatComponentText(
                EnumChatFormatting.GOLD + "====================================="));
        mc.thePlayer.addChatMessage(new ChatComponentText(
                EnumChatFormatting.YELLOW + "点击上方选项选择你想要的奖励，或输入 /hypclaim select <序号>"));
    }

    static void showConfirmation(int index) {
        Minecraft mc = Minecraft.getMinecraft();
        EnumChatFormatting color = getRarityColor(
                index < claimOptionRarities.size() ? claimOptionRarities.get(index) : "");
        mc.thePlayer.addChatMessage(new ChatComponentText(
                EnumChatFormatting.AQUA + "你选择了: " + color + claimOptionDescs.get(index)));
        mc.thePlayer.addChatMessage(new ChatComponentText(
                EnumChatFormatting.YELLOW + "确认领取此奖励？"));

        IChatComponent confirmBtn = new ChatComponentText(EnumChatFormatting.GREEN + "  [确认领取]");
        confirmBtn.setChatStyle(new ChatStyle()
                .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/hypclaim confirm"))
                .setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        new ChatComponentText(EnumChatFormatting.GREEN + "点击确认领取")))
                .setColor(EnumChatFormatting.GREEN));
        mc.thePlayer.addChatMessage(confirmBtn);

        IChatComponent reselectBtn = new ChatComponentText(EnumChatFormatting.YELLOW + "  [选错了重选]");
        reselectBtn.setChatStyle(new ChatStyle()
                .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/hypclaim reselect"))
                .setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        new ChatComponentText(EnumChatFormatting.YELLOW + "点击重新选择奖励")))
                .setColor(EnumChatFormatting.YELLOW));
        mc.thePlayer.addChatMessage(reselectBtn);

        IChatComponent cancelBtn = new ChatComponentText(EnumChatFormatting.RED + "  [取消]");
        cancelBtn.setChatStyle(new ChatStyle()
                .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/hypclaim cancel"))
                .setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        new ChatComponentText(EnumChatFormatting.RED + "点击取消领取")))
                .setColor(EnumChatFormatting.RED));
        mc.thePlayer.addChatMessage(cancelBtn);
    }

    static void claimReward() {
        if (claimRewardCode == null || claimSelectedIndex < 0) {
            sendChat(EnumChatFormatting.RED + "[HypReward] 没有待领取的奖励");
            return;
        }

        sendChat(EnumChatFormatting.YELLOW + "[HypReward] 正在领取奖励...");

        new Thread(() -> {
            try {
                BasicCookieStore cookieStore = new BasicCookieStore();
                for (Map.Entry<String, String> entry : claimCookies.entrySet()) {
                    BasicClientCookie cookie = new BasicClientCookie(entry.getKey(), entry.getValue());
                    cookie.setDomain(".hypixel.net");
                    cookie.setPath("/");
                    cookieStore.addCookie(cookie);
                }

                CloseableHttpClient httpClient = HttpClients.custom()
                        .setDefaultCookieStore(cookieStore)
                        .build();

                String claimUrl = "https://rewards.hypixel.net/claim-reward/claim"
                        + "?id=" + claimId
                        + "&option=" + claimSelectedIndex
                        + "&activeAd=" + claimActiveAd
                        + "&_csrf=" + claimCsrfToken;

                HttpPost httpPost = new HttpPost(claimUrl);
                httpPost.setHeader("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
                httpPost.setHeader("Referer", "https://rewards.hypixel.net/claim-reward/" + claimRewardCode);

                CloseableHttpResponse response = httpClient.execute(httpPost);
                int status = response.getStatusLine().getStatusCode();
                String body = EntityUtils.toString(response.getEntity(), "UTF-8");
                response.close();
                httpClient.close();

                if (status == 200) {
                    sendChat(EnumChatFormatting.GREEN + "[HypReward] 奖励领取成功！");
                    sendChat(EnumChatFormatting.GRAY + "服务器返回: " + body.substring(0, Math.min(body.length(), 200)));
                } else {
                    sendChat(EnumChatFormatting.RED + "[HypReward] 领取失败，状态码: " + status);
                    sendChat(EnumChatFormatting.RED + "服务器返回: " + body.substring(0, Math.min(body.length(), 200)));
                }

                // Clear session
                clearSession();

            } catch (Exception e) {
                sendChat(EnumChatFormatting.RED + "[HypReward] 领取失败: " + e.getMessage());
                clearSession();
            }
        }).start();
    }

    static void clearSession() {
        claimRewardCode = null;
        claimOptions.clear();
        claimOptionDescs.clear();
        claimOptionRarities.clear();
        claimSelectedIndex = -1;
        claimCsrfToken = null;
        claimId = null;
        claimActiveAd = 0;
        claimCookies.clear();
    }

    static boolean hasActiveSession() {
        return claimRewardCode != null && !claimOptions.isEmpty();
    }

    private String extractAppData(String html) {
        Matcher m = Pattern.compile("window\\.appData\\s*=\\s*'([^']+)'").matcher(html);
        if (m.find()) return m.group(1);
        m = Pattern.compile("window\\.appData\\s*=\\s*\"([^\"]+)\"").matcher(html);
        if (m.find()) return m.group(1);
        return null;
    }

    private String extractSecurityToken(String html) {
        Matcher m = Pattern.compile("window\\.securityToken\\s*=\\s*'([^']+)'").matcher(html);
        if (m.find()) return m.group(1);
        m = Pattern.compile("window\\.securityToken\\s*=\\s*\"([^\"]+)\"").matcher(html);
        if (m.find()) return m.group(1);
        return null;
    }

    private String getStringOrDefault(JsonObject obj, String key, String defaultVal) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return defaultVal;
    }

    static EnumChatFormatting getRarityColor(String rarity) {
        if (rarity == null) return EnumChatFormatting.GREEN;
        switch (rarity) {
            case "common":      return EnumChatFormatting.GREEN;
            case "uncommon":    return EnumChatFormatting.DARK_GREEN;
            case "rare":        return EnumChatFormatting.BLUE;
            case "epic":        return EnumChatFormatting.DARK_PURPLE;
            case "legendary":   return EnumChatFormatting.YELLOW;
            default:            return EnumChatFormatting.GREEN;
        }
    }

    static void sendChat(String msg) {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            if (Minecraft.getMinecraft().thePlayer != null) {
                Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText(msg));
            }
        });
    }
}

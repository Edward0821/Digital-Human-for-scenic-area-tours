package com.scenic.digitalhuman.service;

import com.scenic.digitalhuman.common.BizException;
import com.scenic.digitalhuman.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

/**
 * 图形验证码（第九周 slide9：登录必须验证码/限流，防机器人爬虫）。
 *
 * 实现：后端生成 4 位随机码 → 图片转 base64 下发 → Redis 存 5 分钟（一次性）。
 * 自绘零依赖（BufferedImage + Graphics2D），答辩可指到生成代码逐行讲。
 * 去除易混淆字符（0O1lI），比对忽略大小写，取出即删防重放。
 */
@Service
@RequiredArgsConstructor
public class CaptchaService {

    private static final String KEY_PREFIX = "scenic:captcha:";
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final char[] CHARS = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();

    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();

    /** 生成验证码：返回 captchaId 与 base64 图片（data:image/png;base64,...） */
    public CaptchaImage generate() {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            code.append(CHARS[random.nextInt(CHARS.length)]);
        }
        String captchaId = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set(KEY_PREFIX + captchaId, code.toString(), TTL);
        return new CaptchaImage(captchaId, toBase64Png(code.toString()));
    }

    /** 校验：忽略大小写、一次性（取出即删，防重放） */
    public void verify(String captchaId, String code) {
        if (captchaId == null || code == null) {
            throw new BizException(ErrorCode.CAPTCHA_INVALID, "验证码无效或已过期");
        }
        String key = KEY_PREFIX + captchaId;
        String saved = redis.opsForValue().get(key);
        redis.delete(key); // 一次性：无论对错都作废
        if (saved == null || !saved.equalsIgnoreCase(code.trim())) {
            throw new BizException(ErrorCode.CAPTCHA_INVALID, "验证码无效或已过期");
        }
    }

    /** 自绘 120x40 PNG：4 字符 + 3 条干扰线 + 噪点，转 base64 */
    private String toBase64Png(String code) {
        int w = 120, h = 40;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(245, 247, 250));
        g.fillRect(0, 0, w, h);
        // 干扰线
        for (int i = 0; i < 3; i++) {
            g.setColor(new Color(180 + random.nextInt(60), 180 + random.nextInt(60), 200));
            g.setStroke(new BasicStroke(1.2f));
            g.drawLine(random.nextInt(w), random.nextInt(h), random.nextInt(w), random.nextInt(h));
        }
        // 逐字符绘制（随机颜色 + 轻微旋转）
        g.setFont(new java.awt.Font("Consolas", java.awt.Font.BOLD, 26));
        for (int i = 0; i < code.length(); i++) {
            g.setColor(new Color(40 + random.nextInt(80), 60 + random.nextInt(80), 120 + random.nextInt(100)));
            double rot = (random.nextInt(30) - 15) * Math.PI / 180;
            int x = 12 + i * 26, y = 29;
            g.rotate(rot, x, y);
            g.drawString(String.valueOf(code.charAt(i)), x, y);
            g.rotate(-rot, x, y);
        }
        // 噪点
        for (int i = 0; i < 40; i++) {
            img.setRGB(random.nextInt(w), random.nextInt(h), new Color(200, 205, 215).getRGB());
        }
        g.dispose();
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", bos);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "验证码图片生成失败");
        }
    }

    /** 验证码视图：captchaId + base64 图片 */
    public record CaptchaImage(String captchaId, String imageBase64) {}
}

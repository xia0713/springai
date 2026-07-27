package com.example.springai.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;

@Service
public class ImageUnderstandingService {

    private final ChatClient chatClient;
    private final ChatModel chatModel;

    public ImageUnderstandingService(ChatClient chatClient, ChatModel chatModel) {
        this.chatClient = chatClient;
        this.chatModel = chatModel;
    }

    /**
     * 方式一：上传文件 → 描述图片（推荐用 ChatClient）
     */
    public String describeImage(MultipartFile file, String question) throws IOException {
        final String prompt = (question == null || question.isBlank())
                ? "请详细描述这张图片的内容"
                : question;

        MimeType mimeType = getMimeType(file);

        Resource imageData = new ByteArrayResource(file.getBytes());
        return chatClient.prompt()
                .user(u -> u.text(prompt)
                        .media(mimeType, imageData))
                .call()
                .content();
    }

    /**
     * 方式二：通过 URL 描述图片
     */
    public String describeImageUrl(String imageUrl, String question) throws MalformedURLException {
        final String prompt = (question == null || question.isBlank())
                ? "请详细描述这张图片的内容"
                : question;

        Resource imageResource = new UrlResource(imageUrl);
        MimeType mimeType = MimeTypeUtils.IMAGE_PNG;

        UserMessage userMessage = UserMessage.builder()
                .text(prompt)
                .media(new Media(mimeType, imageResource))
                .build();

        ChatResponse response = chatModel.call(new Prompt(userMessage));
        return response.getResult().getOutput().getText();
    }

    /**
     * 方式三：多张图片对比
     */
    public String compareImages(MultipartFile file1, MultipartFile file2, String question) throws IOException {
        final String prompt = (question == null || question.isBlank())
                ? "请对比这两张图片的异同"
                : question;

        MimeType type1 = getMimeType(file1);
        MimeType type2 = getMimeType(file2);

        Resource data1 = new ByteArrayResource(file1.getBytes());
        Resource data2 = new ByteArrayResource(file2.getBytes());

        return chatClient.prompt()
                .user(u -> u.text(prompt)
                        .media(type1, data1)
                        .media(type2, data2))
                .call()
                .content();
    }

    private MimeType getMimeType(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType != null && !contentType.equals("application/octet-stream")) {
            try {
                return MimeTypeUtils.parseMimeType(contentType);
            } catch (Exception ignored) {
            }
        }
        // 从文件名后缀推断 MIME 类型
        String filename = file.getOriginalFilename();
        if (filename != null) {
            String lower = filename.toLowerCase();
            if (lower.endsWith(".png")) return MimeTypeUtils.IMAGE_PNG;
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MimeTypeUtils.IMAGE_JPEG;
            if (lower.endsWith(".gif")) return MimeTypeUtils.IMAGE_GIF;
            if (lower.endsWith(".webp")) return MimeTypeUtils.parseMimeType("image/webp");
            if (lower.endsWith(".pdf")) return MimeTypeUtils.parseMimeType("application/pdf");
            if (lower.endsWith(".mp4")) return MimeTypeUtils.parseMimeType("video/mp4");
            if (lower.endsWith(".mp3") || lower.endsWith(".mpeg")) return MimeTypeUtils.parseMimeType("audio/mpeg");
            if (lower.endsWith(".txt")) return MimeTypeUtils.parseMimeType("text/plain");
        }
        return MimeTypeUtils.IMAGE_PNG; // 兜底
    }
}

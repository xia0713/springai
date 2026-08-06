package com.example.springai.service;

import com.example.springai.exception.AiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URL;

@Slf4j
@Service
@RequiredArgsConstructor
public class MultimodalService {

    private final ChatClient chatClient;

    /**
     * 通过 URL 分析图片
     */
    public String analyzeImageUrl(String imageUrl, String question) {
        try {
            String response = chatClient.prompt()
                    .user(u -> u
                            .text(question != null ? question : "请详细描述这张图片的内容")
                            .media(MimeTypeUtils.IMAGE_PNG, new UrlResource(new URL(imageUrl)))
                    )
                    .call()
                    .content();

            log.info("图片分析完成(URL) url={}", imageUrl);
            return response;

        } catch (Exception e) {
            log.error("图片分析失败(URL)", e);
            throw new AiException("图片分析失败: " + e.getMessage());
        }
    }

    /**
     * 通过上传文件分析图片
     */
    public String analyzeImageFile(MultipartFile file, String question) {
        try {
            byte[] imageBytes = file.getBytes();
            String contentType = file.getContentType();

            var media = new org.springframework.ai.model.Media(
                    org.springframework.util.MimeType.valueOf(contentType != null ? contentType : "image/png"),
                    new ByteArrayResource(imageBytes)
            );

            var userMessage = new UserMessage(
                    question != null ? question : "请详细描述这张图片的内容",
                    media
            );

            String response = chatClient.prompt()
                    .messages(userMessage)
                    .call()
                    .content();

            log.info("图片分析完成(文件) fileName={}, size={}", file.getOriginalFilename(), imageBytes.length);
            return response;

        } catch (IOException e) {
            log.error("图片读取失败", e);
            throw new AiException("图片读取失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("图片分析失败", e);
            throw new AiException("图片分析失败: " + e.getMessage());
        }
    }
}

package com.smashprofs.server.game;

/**
 * Boundary Member 1 -> Member 3, in-process, nhận chính instance kết quả authoritative bất biến.
 * Implementation phải có thời hạn I/O và hợp tác với interrupt khi shutdown.
 * Trả về bình thường chỉ xác nhận handoff theo hợp đồng implementation, không tự chứng minh DB durable.
 */
@FunctionalInterface
public interface RankingGateway {
    void submitMatchResult(AuthoritativeMatchResult result) throws Exception;
}

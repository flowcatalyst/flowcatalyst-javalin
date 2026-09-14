<?php

declare(strict_types=1);

namespace FlowCatalyst\Outbox\Contracts;

interface OutboxDriver
{
    /**
     * Insert a single message into the outbox.
     *
     * @param array{
     *     id: string,
     *     type: string,
     *     message_group: string|null,
     *     payload: string,
     *     status: int,
     *     created_at: string,
     *     updated_at: string,
     *     client_id: string,
     *     payload_size: int,
     *     headers: array|null
     * } $message
     */
    public function insert(array $message): void;

    /**
     * Insert multiple messages into the outbox.
     *
     * @param array<array{
     *     id: string,
     *     type: string,
     *     message_group: string|null,
     *     payload: string,
     *     status: int,
     *     created_at: string,
     *     updated_at: string,
     *     client_id: string,
     *     payload_size: int,
     *     headers: array|null
     * }> $messages
     */
    public function insertBatch(array $messages): void;
}

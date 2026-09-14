<?php

declare(strict_types=1);

namespace FlowCatalyst\DTOs\Responses;

use FlowCatalyst\DTOs\Process;

/**
 * List of processes returned by GET /api/processes.
 *
 * The platform returns `{items: [...]}`; there is no separate `total`.
 */
final class ProcessList
{
    /**
     * @param Process[] $items
     */
    public function __construct(
        public readonly array $items,
    ) {}

    /**
     * @param array<string, mixed> $data
     */
    public static function fromArray(array $data): self
    {
        /** @var array<int, array<string, mixed>> $rows */
        $rows = $data['items'] ?? [];
        return new self(
            items: array_map(
                fn(array $row) => Process::fromArray($row),
                $rows,
            ),
        );
    }
}

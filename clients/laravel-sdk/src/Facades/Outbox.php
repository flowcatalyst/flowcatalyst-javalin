<?php

declare(strict_types=1);

namespace FlowCatalyst\Facades;

use FlowCatalyst\Outbox\Contracts\OutboxDriver;
use FlowCatalyst\Outbox\Drivers\DatabaseDriver;
use FlowCatalyst\Outbox\DTOs\CreateAuditLogDto;
use FlowCatalyst\Outbox\DTOs\CreateDispatchJobDto;
use FlowCatalyst\Outbox\DTOs\CreateEventDto;
use FlowCatalyst\Outbox\OutboxManager;
use Illuminate\Support\Facades\Facade;

/**
 * @method static string createEvent(CreateEventDto $event, ?OutboxDriver $driver = null)
 * @method static string createDispatchJob(CreateDispatchJobDto $job, ?OutboxDriver $driver = null)
 * @method static string[] createEvents(array $events, ?OutboxDriver $driver = null)
 * @method static string[] createDispatchJobs(array $jobs, ?OutboxDriver $driver = null)
 * @method static string createAuditLog(CreateAuditLogDto $auditLog, ?OutboxDriver $driver = null)
 * @method static string[] createAuditLogs(array $auditLogs, ?OutboxDriver $driver = null)
 * @method static DatabaseDriver driverForConnection(?string $connection, string $table = 'outbox_messages')
 * @method static OutboxDriver driver()
 *
 * @see OutboxManager
 */
class Outbox extends Facade
{
    /**
     * Get the registered name of the component.
     */
    protected static function getFacadeAccessor(): string
    {
        return OutboxManager::class;
    }
}

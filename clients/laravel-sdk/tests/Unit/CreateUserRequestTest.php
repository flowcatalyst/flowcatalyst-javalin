<?php

declare(strict_types=1);

namespace FlowCatalyst\Tests\Unit;

use FlowCatalyst\DTOs\Requests\CreateUserRequest;
use PHPUnit\Framework\TestCase;

/**
 * `sendInvitation` and `returnInviteLink` are optional wire fields — omitted
 * from the payload entirely when null so the platform's defaults (true /
 * false respectively) apply, and only emitted when the caller explicitly
 * sets them.
 */
final class CreateUserRequestTest extends TestCase
{
    public function test_omits_invitation_flags_when_absent(): void
    {
        $r = new CreateUserRequest(
            email: 'a@example.com',
            name: 'Alice',
        );

        $payload = $r->toArray();

        self::assertArrayNotHasKey('sendInvitation', $payload);
        self::assertArrayNotHasKey('returnInviteLink', $payload);
        self::assertSame([
            'email' => 'a@example.com',
            'name' => 'Alice',
        ], $payload);
    }

    public function test_includes_invitation_flags_when_present(): void
    {
        $r = new CreateUserRequest(
            email: 'a@example.com',
            name: 'Alice',
            sendInvitation: false,
            returnInviteLink: true,
        );

        $payload = $r->toArray();

        self::assertArrayHasKey('sendInvitation', $payload);
        self::assertFalse($payload['sendInvitation']);
        self::assertArrayHasKey('returnInviteLink', $payload);
        self::assertTrue($payload['returnInviteLink']);
    }

    public function test_includes_explicit_false_return_invite_link(): void
    {
        $r = new CreateUserRequest(
            email: 'a@example.com',
            name: 'Alice',
            sendInvitation: true,
            returnInviteLink: false,
        );

        $payload = $r->toArray();

        self::assertTrue($payload['sendInvitation']);
        self::assertFalse($payload['returnInviteLink']);
    }
}

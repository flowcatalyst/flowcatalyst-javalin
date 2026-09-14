# Syncing Definitions to FlowCatalyst

This guide covers how to sync your application's roles, event types, and subscriptions to the FlowCatalyst platform.

There are two approaches:

1. **Attribute-based** - Define your definitions as PHP classes with attributes, scan them, and sync via artisan command
2. **Programmatic** - Build definition sets in code and sync via the `DefinitionSynchronizer` service

Choose the approach that best fits your needs:

| Approach        | Best For                                                        |
| --------------- | --------------------------------------------------------------- |
| Attribute-based | Single-app deployments, code-first definitions, CI/CD pipelines |
| Programmatic    | Multi-app deployments, dynamic definitions, custom sync logic   |

---

## Approach 1: Attribute-Based Sync

This approach uses PHP 8 attributes to define your roles, event types, and subscriptions as classes. An artisan command scans these classes and syncs them to the platform.

### Step 1: Configure Your Application

Add to your `.env`:

```env
FLOWCATALYST_APP_CODE=my-application
```

Optionally configure the scan paths in `config/flowcatalyst.php`:

```php
'definitions' => [
    'paths' => [
        app_path('FlowCatalyst'),        // Default
        app_path('Domain/Events'),       // Additional paths
    ],
    'cache_path' => storage_path('flowcatalyst'),
],
```

#### Which application a definition belongs to

Every definition is synced to one application's endpoint. The code is resolved
per definition, in this order:

1. **`application:` on the attribute** — supported by *every* definition
   attribute (`AsScheduledJob`, `AsEventType`, `AsRole`, `AsSubscription`,
   `AsDispatchPool`, `AsProcess`, `AsPermission`).
2. **Longest-prefix match** in `flowcatalyst.definitions.application_map` —
   maps a namespace onto an application code, so a whole module inherits one.
3. **The default** — `--app` on the command, else `FLOWCATALYST_APP_CODE`.

So the common case needs nothing: set `FLOWCATALYST_APP_CODE` and every
definition follows it. Reach for `application:` only when one codebase owns
definitions for more than one application:

```php
#[AsScheduledJob(
    code: MeasurementScheduledJobCode::PARTITION_MAINTENANCE->value,
    name: 'Measurement partition maintenance',
    crons: ['0 0 2 * * *'],
    description: 'Create upcoming partitions and drop those outside retention.',
    timezone: 'UTC',
    concurrent: false,
    tracksCompletion: true,
    timeoutSeconds: 120,
    application: 'measurement',   // overrides the map and FLOWCATALYST_APP_CODE
)]
class PartitionMaintenanceJob {}
```

Or map a whole namespace instead of annotating each class:

```php
'definitions' => [
    'application_map' => [
        'App\\Measurement\\' => 'measurement',
        'App\\Billing\\'     => 'billing',
    ],
],
```

`flowcatalyst:sync` partitions the scanned definitions by resolved application
and syncs each bucket to its own endpoint, reporting one block per application.
A definition that resolves *no* application and has no default is reported and
skipped — it never blocks the other applications from syncing.

`application:` is a routing axis only. It decides which application's sync
endpoint the definition is posted to (and, for codes that carry an application
segment, which prefix is baked in) — it is never sent as a field in the request
body.

> **Caveat when moving definitions between applications.** `--remove-unlisted`
> is full-replace *per application, per category*, and an application is only
> contacted if at least one definition resolves to it. If you move the last
> scheduled job out of `billing`, `billing` is not part of the next sync at all,
> so its now-orphaned jobs are **not** pruned — they stay on the platform until
> you remove them by hand or sync that application with an explicit `--app`.

### Step 2: Create Definition Classes

Create PHP classes with FlowCatalyst attributes in your configured paths.

#### Roles

```php
<?php
// app/FlowCatalyst/Roles/AdminRole.php

namespace App\FlowCatalyst\Roles;

use FlowCatalyst\Attributes\AsRole;

#[AsRole(
    name: 'admin',
    displayName: 'Administrator',
    description: 'Full system access',
    permissions: ['user:manage', 'content:manage', 'settings:manage'],
    clientManaged: false
)]
class AdminRole {}
```

```php
<?php
// app/FlowCatalyst/Roles/EditorRole.php

namespace App\FlowCatalyst\Roles;

use FlowCatalyst\Attributes\AsRole;

#[AsRole(
    name: 'editor',
    displayName: 'Editor',
    description: 'Can manage content',
    permissions: ['content:read', 'content:write', 'content:publish'],
    clientManaged: true  // Clients can assign this role to their users
)]
class EditorRole {}
```

#### Permissions

Permissions can be inlined on a role (as `PermissionInput` objects or
`app:context:aggregate:action` strings), but since a permission is usually
shared across roles you can define it **once** with `#[AsPermission]` and link
roles to it by class:

```php
<?php
// app/FlowCatalyst/Permissions/ViewPosts.php
namespace App\FlowCatalyst\Permissions;

use FlowCatalyst\Attributes\AsPermission;

// The `application` segment defaults to your app code, so it becomes
// "<app>:posts:post:view".
#[AsPermission(context: 'posts', aggregate: 'post', action: 'view', description: 'View posts')]
class ViewPosts {}
```

```php
<?php
// app/FlowCatalyst/Roles/EditorRole.php
namespace App\FlowCatalyst\Roles;

use App\FlowCatalyst\Permissions\EditPosts;
use App\FlowCatalyst\Permissions\ViewPosts;
use FlowCatalyst\Attributes\AsRole;

#[AsRole(
    name: 'editor',
    displayName: 'Editor',
    permissions: [ViewPosts::class, EditPosts::class], // linked, not duplicated
)]
class EditorRole {}
```

How permissions sync:

- **To the platform** — FlowCatalyst has no standalone "create permission"
  endpoint; permissions exist by being granted to a role. So they ride up via
  the roles that reference them. A permission not used by any role won't reach
  the platform (but is still seeded locally, below).
- **To Spatie (local)** — when `spatie/laravel-permission` is installed,
  `flowcatalyst:sync` also mirrors the scanned roles + permissions into the
  local tables (under `flowcatalyst.oidc.roles_guard`), so your app's
  authorization model matches what it pushed. Roles are stored under their
  platform name (e.g. `example:editor`). Toggle with
  `flowcatalyst.definitions.seed_spatie` or `--no-spatie`.
- **On login** — the OIDC handler additionally pulls each of the signed-in
  user's roles' permissions from the platform and mirrors them onto the local
  Spatie role, so `$user->can('app:context:aggregate:action')` works for roles
  granted on the platform (not just locally defined ones). Toggle with
  `flowcatalyst.oidc.sync_role_permissions`.

#### Event Types

```php
<?php
// app/FlowCatalyst/Events/UserCreatedEvent.php

namespace App\FlowCatalyst\Events;

use FlowCatalyst\Attributes\AsEventType;

#[AsEventType(
    code: 'user.created',
    name: 'User Created',
    description: 'Fired when a new user registers'
)]
class UserCreatedEvent {}
```

```php
<?php
// app/FlowCatalyst/Events/OrderPlacedEvent.php

namespace App\FlowCatalyst\Events;

use FlowCatalyst\Attributes\AsEventType;

#[AsEventType(
    code: 'order.placed',
    name: 'Order Placed',
    description: 'Fired when a customer places an order'
)]
class OrderPlacedEvent {}
```

#### Subscriptions

```php
<?php
// app/FlowCatalyst/Subscriptions/OrderNotificationSubscription.php

namespace App\FlowCatalyst\Subscriptions;

use FlowCatalyst\Attributes\AsSubscription;

#[AsSubscription(
    code: 'order-notifications',
    name: 'Order Notifications',
    target: 'https://myapp.com/webhooks/orders',
    queue: 'orders',
    dispatchPoolCode: 'default',
    eventTypeCode: 'order.placed',  // Subscribe to specific event type
    description: 'Sends order notifications to our webhook',
    maxRetries: 5,
    retryDelaySeconds: 120,
    timeoutSeconds: 30,
    active: true
)]
class OrderNotificationSubscription {}
```

### Step 3: Scan Definitions

Run the scan command to find and cache your definitions:

```bash
php artisan flowcatalyst:scan
```

Output:

```
Scanning paths for FlowCatalyst definitions...
  - /var/www/app/FlowCatalyst

Scan complete!
+---------------+-------+
| Type          | Count |
+---------------+-------+
| Roles         | 2     |
| Event Types   | 2     |
| Subscriptions | 1     |
+---------------+-------+

Definitions cached to: /var/www/storage/flowcatalyst/definitions.json
```

Options:

- `--clear` - Clear the cache before scanning

### Step 4: Sync to Platform

Sync your cached definitions to FlowCatalyst:

```bash
php artisan flowcatalyst:sync
```

Output:

```
Syncing definitions to application: my-application

Sync Summary:
+---------------+---------+---------+---------+
| Type          | Created | Updated | Deleted |
+---------------+---------+---------+---------+
| Roles         | 2       | 0       | 0       |
| Event Types   | 2       | 0       | 0       |
| Subscriptions | 1       | 0       | 0       |
+---------------+---------+---------+---------+
```

Options:

- `--app=code` - Override the application code from config
- `--roles` - Sync only roles
- `--event-types` - Sync only event types
- `--subscriptions` - Sync only subscriptions
- `--remove-unlisted` - Remove platform definitions not in your local cache (only removes API-sourced definitions, not UI-created ones)
- `--dry-run` - Preview what would be synced without making changes

### CI/CD Integration

Add to your deployment pipeline:

```yaml
# .github/workflows/deploy.yml
- name: Sync FlowCatalyst definitions
  run: |
    php artisan flowcatalyst:scan
    php artisan flowcatalyst:sync --remove-unlisted
```

---

## Approach 2: Programmatic Sync

This approach gives you full control over what gets synced. Use it when:

- Your deployment manages multiple FlowCatalyst applications
- Definitions are dynamic or loaded from a database
- You need custom sync logic or error handling

### Basic Usage

```php
<?php

use FlowCatalyst\Sync\DefinitionSynchronizer;
use FlowCatalyst\Sync\SyncDefinitionSet;
use FlowCatalyst\Sync\RoleDefinition;
use FlowCatalyst\Sync\EventTypeDefinition;
use FlowCatalyst\Sync\SubscriptionDefinition;

// Get the synchronizer from the container
$synchronizer = app(DefinitionSynchronizer::class);

// Build your definition set
$definitions = SyncDefinitionSet::forApplication('my-app')
    ->withRoles([
        new RoleDefinition(
            name: 'admin',
            displayName: 'Administrator',
            permissions: ['user:manage', 'settings:manage']
        ),
        new RoleDefinition(
            name: 'viewer',
            displayName: 'Viewer',
            permissions: ['content:read']
        ),
    ])
    ->withEventTypes([
        new EventTypeDefinition(
            code: 'user.created',
            name: 'User Created',
            description: 'Fired when a user is created'
        ),
    ]);

// Sync to the platform
$result = $synchronizer->sync($definitions);

// Check results
if ($result->hasErrors()) {
    foreach ($result->getErrors() as $type => $error) {
        Log::error("Failed to sync {$type}: {$error}");
    }
}

echo "Created: " . $result->getTotals()['created'];
```

### Using Arrays Instead of DTOs

You can use plain arrays if you prefer:

```php
$definitions = SyncDefinitionSet::forApplication('my-app')
    ->withRoles([
        [
            'name' => 'admin',
            'displayName' => 'Administrator',
            'permissions' => ['user:manage'],
        ],
    ])
    ->withEventTypes([
        [
            'code' => 'user.created',
            'name' => 'User Created',
        ],
    ])
    ->withSubscriptions([
        [
            'code' => 'my-subscription',
            'name' => 'My Subscription',
            'target' => 'https://example.com/webhook',
            'queue' => 'default',
            'dispatchPoolCode' => 'default',
        ],
    ]);
```

### Using the Fluent Builder

DTOs support a fluent builder pattern:

```php
$role = RoleDefinition::make('editor')
    ->withDisplayName('Content Editor')
    ->withDescription('Can edit and publish content')
    ->withPermissions(['content:read', 'content:write', 'content:publish'])
    ->clientManaged();

$eventType = EventTypeDefinition::make('article.published', 'Article Published')
    ->withDescription('Fired when an article is published');

$subscription = SubscriptionDefinition::make(
    code: 'article-webhook',
    name: 'Article Webhook',
    target: 'https://example.com/webhooks/articles',
    queue: 'webhooks',
    dispatchPoolCode: 'default'
)
    ->forEventType('article.published')
    ->withRetry(maxRetries: 5, delaySeconds: 60)
    ->withTimeout(45);
```

### Sync Options

Control what gets synced with `SyncOptions`:

```php
use FlowCatalyst\Sync\SyncOptions;

// Default options (sync everything, don't remove unlisted)
$result = $synchronizer->sync($definitions);

// Remove definitions not in your local set
$result = $synchronizer->sync($definitions, SyncOptions::withRemoveUnlisted());

// Sync only roles
$result = $synchronizer->sync($definitions, SyncOptions::rolesOnly());

// Sync only event types
$result = $synchronizer->sync($definitions, SyncOptions::eventTypesOnly());

// Custom options
$options = new SyncOptions(
    removeUnlisted: true,
    syncRoles: true,
    syncEventTypes: true,
    syncSubscriptions: false,  // Skip subscriptions
);
$result = $synchronizer->sync($definitions, $options);
```

### Multi-Application Sync

For deployments that manage multiple FlowCatalyst applications:

```php
// Define definitions for each application
$billingDefinitions = SyncDefinitionSet::forApplication('billing-service')
    ->withEventTypes([
        new EventTypeDefinition('invoice.created', 'Invoice Created'),
        new EventTypeDefinition('payment.received', 'Payment Received'),
    ])
    ->withRoles([
        new RoleDefinition('billing-admin', 'Billing Admin'),
    ]);

$crmDefinitions = SyncDefinitionSet::forApplication('crm-service')
    ->withEventTypes([
        new EventTypeDefinition('lead.created', 'Lead Created'),
        new EventTypeDefinition('deal.won', 'Deal Won'),
    ])
    ->withSubscriptions([
        new SubscriptionDefinition(
            code: 'billing-integration',
            name: 'Billing Integration',
            target: 'https://crm.example.com/webhooks/billing',
            queue: 'integrations',
            dispatchPoolCode: 'default',
            eventTypeCode: 'invoice.created',
        ),
    ]);

// Sync all applications at once
$results = $synchronizer->syncAll(
    [$billingDefinitions, $crmDefinitions],
    SyncOptions::withRemoveUnlisted()
);

// Process results per application
foreach ($results as $appCode => $result) {
    if ($result->hasErrors()) {
        Log::error("Sync failed for {$appCode}", [
            'errors' => $result->getErrors(),
        ]);
    } else {
        Log::info("Synced {$appCode}", [
            'totals' => $result->getTotals(),
        ]);
    }
}
```

### Building Definitions Dynamically

Load definitions from a database or configuration:

```php
// From database
$tenants = Tenant::where('sync_enabled', true)->get();

$definitionSets = $tenants->map(function ($tenant) {
    return SyncDefinitionSet::forApplication($tenant->flowcatalyst_app_code)
        ->withEventTypes(
            $tenant->eventTypes->map(fn($et) => new EventTypeDefinition(
                code: $et->code,
                name: $et->name,
                description: $et->description,
            ))->toArray()
        );
})->toArray();

$results = $synchronizer->syncAll($definitionSets);
```

```php
// From configuration file
$apps = config('flowcatalyst-apps');

foreach ($apps as $appConfig) {
    $definitions = SyncDefinitionSet::forApplication($appConfig['code'])
        ->withEventTypes(array_map(
            fn($et) => new EventTypeDefinition($et['code'], $et['name']),
            $appConfig['event_types']
        ));

    $synchronizer->sync($definitions);
}
```

### Syncing users (principals) and migrating their passwords

`PrincipalDefinition` syncs a user (matched by email) and their roles. By
default a synced user has **no password** — it is created as an OIDC-style
identity, and a password login returns "Invalid credentials". That is correct
when users authenticate through the platform's OIDC.

To let your existing Laravel users keep signing in with the **same password**,
pass their already-hashed credential via `withPasswordHash()`. The hash is sent
verbatim (never re-hashed); the platform accepts Laravel's `bcrypt` and
`argon2i` formats, verifies the password as-is at login, and transparently
re-encodes it to its native scheme on the first successful sign-in.

```php
use FlowCatalyst\Sync\PrincipalDefinition;
use FlowCatalyst\Sync\SyncDefinitionSet;

$principals = User::query()->get()->map(
    fn (User $u) => PrincipalDefinition::make($u->email, $u->name)
        ->withRoles(['admin'])
        // $u->getAuthPassword() is the stored hash (the `password` column),
        // NOT a plaintext password. Pass the HASH.
        ->withPasswordHash($u->getAuthPassword())
)->all();

$definitions = SyncDefinitionSet::forApplication('orders')
    ->withPrincipals($principals);

$synchronizer->sync($definitions);
```

Notes:
- **Pass the hash, not the plaintext.** `withPasswordHash()` expects a bcrypt/
  argon2i hash (e.g. `$2y$...` or `$argon2i$...`). A plaintext value would be
  stored as-is and never match at login.
- **Omitting the hash leaves any existing password untouched** — a later
  roles-only sync will not wipe a password the user already has. Supplying a
  hash overwrites the stored one.
- Leave it out entirely for users who authenticate via OIDC.

#### Just syncing users? Skip the application.

Users are **global** (matched by email) — they are not owned by an application —
so if you are *only* migrating users (and their passwords), you don't need an
application code at all. Use the resource-level `syncUsers()`, which posts to the
application-less `POST /api/principals/sync`:

```php
use FlowCatalyst\DTOs\Requests\SyncPrincipalEntry;

$entries = User::query()->get()->map(
    fn (User $u) => new SyncPrincipalEntry(
        email: $u->email,
        name: $u->name,
        roles: [],                          // optional
        passwordHash: $u->getAuthPassword() // the HASH, not plaintext
    )
)->all();

$result = $client->principals()->syncUsers($entries);
// $result->created / ->updated / ->syncedCodes (the synced emails)
```

This is a pure upsert keyed on email — it never strips roles from unlisted
users — and needs none of the application ceremony of the per-app
`sync($appCode, ...)`. The same `syncUsers` exists in the TypeScript SDK
(`client.principals().syncUsers([...])`) and the Go SDK
(`client.Principals().SyncUsers(...)` / `synchronizer.SyncUsers(...)`).

### Error Handling

```php
$result = $synchronizer->sync($definitions);

// Check for any errors
if ($result->hasErrors()) {
    $errors = $result->getErrors();
    // ['roles' => 'Connection timeout', 'eventTypes' => 'Validation failed']
}

// Check specific types
if (isset($result->roles['error'])) {
    Log::error('Role sync failed: ' . $result->roles['error']);
}

// Get counts even when there are partial failures
$totals = $result->getTotals();
// ['created' => 5, 'updated' => 2, 'deleted' => 1]
```

### Integrating with a Service Provider

For multi-app deployments, create a dedicated service provider:

```php
<?php
// app/Providers/FlowCatalystSyncProvider.php

namespace App\Providers;

use FlowCatalyst\Sync\DefinitionSynchronizer;
use FlowCatalyst\Sync\SyncDefinitionSet;
use FlowCatalyst\Sync\EventTypeDefinition;
use Illuminate\Support\ServiceProvider;

class FlowCatalystSyncProvider extends ServiceProvider
{
    public function boot(DefinitionSynchronizer $synchronizer): void
    {
        // Sync on deployment (or call from a command)
        if ($this->app->runningInConsole() && $this->shouldSync()) {
            $this->syncAllApplications($synchronizer);
        }
    }

    private function syncAllApplications(DefinitionSynchronizer $synchronizer): void
    {
        $results = $synchronizer->syncAll([
            $this->getBillingDefinitions(),
            $this->getCrmDefinitions(),
        ]);

        foreach ($results as $appCode => $result) {
            if ($result->hasErrors()) {
                logger()->error("FlowCatalyst sync failed for {$appCode}", $result->getErrors());
            }
        }
    }

    private function getBillingDefinitions(): SyncDefinitionSet
    {
        return SyncDefinitionSet::forApplication('billing')
            ->withEventTypes([
                new EventTypeDefinition('invoice.created', 'Invoice Created'),
                new EventTypeDefinition('payment.received', 'Payment Received'),
            ]);
    }

    private function getCrmDefinitions(): SyncDefinitionSet
    {
        return SyncDefinitionSet::forApplication('crm')
            ->withEventTypes([
                new EventTypeDefinition('lead.created', 'Lead Created'),
            ]);
    }

    private function shouldSync(): bool
    {
        return config('flowcatalyst.auto_sync', false);
    }
}
```

---

## API Reference

### RoleDefinition

| Property        | Type     | Required | Description                                           |
| --------------- | -------- | -------- | ----------------------------------------------------- |
| `name`          | string   | Yes      | Unique role identifier                                |
| `displayName`   | string   | No       | Human-readable name                                   |
| `description`   | string   | No       | Role description                                      |
| `permissions`   | string[] | No       | Permission codes this role grants                     |
| `clientManaged` | bool     | No       | Whether clients can assign this role (default: false) |

### EventTypeDefinition

| Property      | Type   | Required | Description            |
| ------------- | ------ | -------- | ---------------------- |
| `code`        | string | Yes      | Unique event type code |
| `name`        | string | Yes      | Human-readable name    |
| `description` | string | No       | Event type description |

### SubscriptionDefinition

| Property            | Type   | Required | Description                                    |
| ------------------- | ------ | -------- | ---------------------------------------------- |
| `code`              | string | Yes      | Unique subscription code                       |
| `name`              | string | Yes      | Human-readable name                            |
| `target`            | string | Yes      | Webhook URL                                    |
| `queue`             | string | Yes      | Queue name for delivery                        |
| `dispatchPoolCode`  | string | Yes      | Dispatch pool code                             |
| `description`       | string | No       | Subscription description                       |
| `eventTypeCode`     | string | No       | Event type to subscribe to                     |
| `maxRetries`        | int    | No       | Max retry attempts (default: 3)                |
| `retryDelaySeconds` | int    | No       | Delay between retries (default: 60)            |
| `timeoutSeconds`    | int    | No       | Webhook timeout (default: 30)                  |
| `active`            | bool   | No       | Whether subscription is active (default: true) |

### SyncResult

| Method                     | Returns | Description                        |
| -------------------------- | ------- | ---------------------------------- |
| `hasChanges()`             | bool    | Whether any changes were made      |
| `hasErrors()`              | bool    | Whether any errors occurred        |
| `getErrors()`              | array   | Error messages by type             |
| `getTotals()`              | array   | Aggregate counts across all types  |
| `hasRoleChanges()`         | bool    | Whether roles were changed         |
| `hasEventTypeChanges()`    | bool    | Whether event types were changed   |
| `hasSubscriptionChanges()` | bool    | Whether subscriptions were changed |

### SyncOptions Factory Methods

| Method                              | Description                           |
| ----------------------------------- | ------------------------------------- |
| `SyncOptions::defaults()`           | Sync all types, don't remove unlisted |
| `SyncOptions::withRemoveUnlisted()` | Sync all, remove unlisted             |
| `SyncOptions::rolesOnly()`          | Only sync roles                       |
| `SyncOptions::eventTypesOnly()`     | Only sync event types                 |
| `SyncOptions::subscriptionsOnly()`  | Only sync subscriptions               |

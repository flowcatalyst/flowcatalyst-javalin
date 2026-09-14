<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Run the migrations.
     */
    public function up(): void
    {
        Schema::create('outbox_messages', function (Blueprint $table) {
            // Columns required by the Java outbox-processor
            $table->string('id', 26)->primary();
            $table->string('type', 20);
            $table->string('message_group', 255)->nullable();
            $table->longText('payload');
            $table->smallInteger('status')->default(0);
            $table->smallInteger('retry_count')->default(0);
            $table->timestamp('created_at')->useCurrent();
            $table->timestamp('updated_at')->useCurrent();
            $table->text('error_message')->nullable();

            // SDK-specific columns (ignored by the processor)
            $table->string('client_id', 26)->nullable();
            $table->integer('payload_size')->nullable();
            $table->json('headers')->nullable();

            // Processor indexes: pending items (status=0)
            $table->index(
                ['status', 'message_group', 'created_at'],
                'idx_outbox_messages_pending'
            );

            // Processor indexes: stuck items (status=9)
            $table->index(
                ['status', 'created_at'],
                'idx_outbox_messages_stuck'
            );

            // SDK-specific: client polling
            $table->index(
                ['client_id', 'status', 'created_at'],
                'idx_outbox_client_pending'
            );
        });
    }

    /**
     * Reverse the migrations.
     */
    public function down(): void
    {
        Schema::dropIfExists('outbox_messages');
    }
};

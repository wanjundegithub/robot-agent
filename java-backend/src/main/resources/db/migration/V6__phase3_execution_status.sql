ALTER TABLE `execution`
    MODIFY COLUMN `status` ENUM(
        'pending',
        'running',
        'waiting_user',
        'waiting_tool',
        'suspended',
        'completed',
        'failed',
        'cancelled'
    ) DEFAULT 'pending';

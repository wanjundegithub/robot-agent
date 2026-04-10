ALTER TABLE `execution`
    ADD COLUMN IF NOT EXISTS `client_message_id` VARCHAR(64) NULL;

CREATE INDEX `idx_execution_client_message`
    ON `execution` (`session_id`, `client_message_id`);

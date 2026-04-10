INSERT INTO `workflow_definition` (
    `workspace_id`,
    `workflow_code`,
    `name`,
    `description`,
    `status`,
    `current_version`,
    `created_by`
)
SELECT
    1,
    'flight_booking',
    'Flight Booking Demo',
    'Phase 1 closed-loop demo workflow.',
    'PUBLISHED',
    '1.0.0',
    'system'
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1
    FROM `workflow_definition`
    WHERE `workspace_id` = 1
      AND `workflow_code` = 'flight_booking'
);

INSERT INTO `workflow_version` (
    `workflow_code`,
    `version`,
    `status`,
    `definition`,
    `entry_rule`,
    `config`,
    `created_by`,
    `published_at`
)
SELECT
    'flight_booking',
    '1.0.0',
    'PUBLISHED',
    '{"workflow_code":"flight_booking","workflow_version":"1.0.0","entry":"start","nodes":{"start":{"id":"start","type":"start"},"extract_slots":{"id":"extract_slots","type":"llm"},"check_slots":{"id":"check_slots","type":"condition","config":{"required_fields":["departure_city","arrival_city","departure_date"]}},"collect_info":{"id":"collect_info","type":"form"},"end":{"id":"end","type":"end"}},"transitions":{"start":"extract_slots","extract_slots":"check_slots","check_slots":{"complete":"end","missing":"collect_info"},"collect_info":"end","end":null}}',
    '{"intent_codes":["flight_booking"],"keywords":["flight","ticket","booking","航班","机票","订票"],"priority":100}',
    '{"demo":true}',
    'system',
    CURRENT_TIMESTAMP
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1
    FROM `workflow_version`
    WHERE `workflow_code` = 'flight_booking'
      AND `version` = '1.0.0'
);

UPDATE `workflow_definition`
SET `status` = 'PUBLISHED',
    `current_version` = '1.0.0'
WHERE `workspace_id` = 1
  AND `workflow_code` = 'flight_booking';

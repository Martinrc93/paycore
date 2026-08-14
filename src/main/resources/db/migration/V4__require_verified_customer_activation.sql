ALTER TABLE customers DROP CONSTRAINT ck_customers_status;

ALTER TABLE customers ADD CONSTRAINT ck_customers_status CHECK (status IN (
    'PROVISIONING', 'PENDING_VERIFICATION', 'ACTIVE', 'PROVISIONING_FAILED', 'SUSPENDED', 'BLOCKED'
));

DELETE FROM spring_session_attributes
 WHERE session_primary_id IN (
     SELECT s.primary_id
       FROM spring_session s
       JOIN customers c ON c.id::text = s.principal_name
      WHERE c.status = 'ACTIVE'
 );

DELETE FROM spring_session
 WHERE principal_name IN (
     SELECT id::text FROM customers WHERE status = 'ACTIVE'
 );

UPDATE customers
   SET status = 'PENDING_VERIFICATION',
       updated_at = CURRENT_TIMESTAMP,
       version = version + 1
 WHERE status = 'ACTIVE';

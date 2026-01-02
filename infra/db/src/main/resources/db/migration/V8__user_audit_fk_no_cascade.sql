ALTER TABLE user_audits
    DROP CONSTRAINT IF EXISTS user_audits_actor_id_fkey,
    DROP CONSTRAINT IF EXISTS user_audits_target_user_id_fkey;

ALTER TABLE user_audits
    ADD CONSTRAINT user_audits_actor_id_fkey
    FOREIGN KEY (actor_id) REFERENCES users(id) ON DELETE RESTRICT,
    ADD CONSTRAINT user_audits_target_user_id_fkey
    FOREIGN KEY (target_user_id) REFERENCES users(id) ON DELETE RESTRICT;

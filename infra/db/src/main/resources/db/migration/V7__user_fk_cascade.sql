ALTER TABLE user_roles
    DROP CONSTRAINT IF EXISTS user_roles_user_id_fkey,
    DROP CONSTRAINT IF EXISTS user_roles_assigned_by_fkey;

ALTER TABLE user_roles
    ADD CONSTRAINT user_roles_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    ADD CONSTRAINT user_roles_assigned_by_fkey
    FOREIGN KEY (assigned_by) REFERENCES users(id) ON DELETE CASCADE;

"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { apiFetch } from "@/lib/api";

type User = {
  id: string;
  email?: string | null;
  displayName?: string | null;
  roles: string[];
  status: "active" | "disabled" | string;
};

type CreateUserPayload = {
  email?: string;
  displayName?: string;
  roles: string[];
};

type UserListResponse = {
  items: User[];
  nextCursor?: string | null;
};

type RoleEditState = {
  roles: Set<string>;
  reason: string;
};

const ROLE_OPTIONS = ["reader", "editor", "reviewer", "admin"] as const;

export default function AdminUsersPage() {
  const [users, setUsers] = useState<User[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [createForm, setCreateForm] = useState<CreateUserPayload>({
    email: "",
    displayName: "",
    roles: ["reader"]
  });
  const [roleEdits, setRoleEdits] = useState<Record<string, RoleEditState>>({});
  const [statusReasons, setStatusReasons] = useState<Record<string, string>>({});
  const [deleteReasons, setDeleteReasons] = useState<Record<string, string>>({});
  const [showDeleted, setShowDeleted] = useState(false);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [submitStatus, setSubmitStatus] = useState<string | null>(null);

  const loadUsers = useCallback(async (options?: {
    preserve?: boolean;
    cursor?: string | null;
    append?: boolean;
  }) => {
    if (!options?.preserve) {
      setLoading(true);
    }
    setError(null);
    try {
      const params = new URLSearchParams();
      if (showDeleted) {
        params.set("include_deleted", "true");
      }
      if (options?.cursor) {
        params.set("cursor", options.cursor);
      }
      const query = params.toString() ? `?${params.toString()}` : "";
      const data = await apiFetch<User[] | UserListResponse>(`/v1/users${query}`);
      const items = Array.isArray(data) ? data : data.items;
      const cursor = Array.isArray(data) ? null : data.nextCursor || null;
      setNextCursor(cursor);
      setUsers((prev) => (options?.append ? [...prev, ...items] : items));
      setRoleEdits((prev) => {
        const next = { ...prev };
        items.forEach((user) => {
          if (!next[user.id]) {
            next[user.id] = {
              roles: new Set(user.roles || []),
              reason: ""
            };
          }
        });
        return next;
      });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load users.");
    } finally {
      if (!options?.preserve) {
        setLoading(false);
      }
    }
  }, [showDeleted]);

  useEffect(() => {
    void loadUsers();
  }, [loadUsers]);

  const submitCreateUser = async () => {
    setSubmitStatus(null);
    try {
      await apiFetch<User>("/v1/users", {
        method: "POST",
        body: JSON.stringify({
          email: createForm.email || undefined,
          displayName: createForm.displayName || undefined,
          roles: createForm.roles
        })
      });
      setCreateForm({ email: "", displayName: "", roles: ["reader"] });
      await loadUsers({ preserve: true });
      setSubmitStatus("User created.");
    } catch (err) {
      setSubmitStatus(err instanceof Error ? err.message : "Create failed.");
    }
  };

  const updateRoles = async (userId: string) => {
    const edit = roleEdits[userId];
    if (!edit) {
      return;
    }
    setSubmitStatus(null);
    try {
      const updated = await apiFetch<User>(`/v1/users/${userId}/roles`, {
        method: "PATCH",
        body: JSON.stringify({
          roles: Array.from(edit.roles),
          reason: edit.reason || "role update"
        })
      });
      setUsers((prev) =>
        prev.map((user) => (user.id === userId ? updated : user))
      );
      setSubmitStatus("Roles updated.");
    } catch (err) {
      setSubmitStatus(err instanceof Error ? err.message : "Role update failed.");
    }
  };

  const toggleStatus = async (user: User) => {
    const reason = statusReasons[user.id] || "status change";
    const nextStatus = user.status === "active" ? "disabled" : "active";
    setSubmitStatus(null);
    try {
      const updated = await apiFetch<User>(`/v1/users/${user.id}/status`, {
        method: "PATCH",
        body: JSON.stringify({
          status: nextStatus,
          reason
        })
      });
      setUsers((prev) =>
        prev.map((entry) => (entry.id === user.id ? updated : entry))
      );
      setSubmitStatus("Status updated.");
    } catch (err) {
      setSubmitStatus(err instanceof Error ? err.message : "Status update failed.");
    }
  };

  const deleteUser = async (user: User) => {
    const reason = deleteReasons[user.id] || "";
    if (!reason.trim()) {
      setSubmitStatus("Delete reason is required.");
      return;
    }
    const confirmed = window.confirm(
      `Delete ${user.displayName || user.email || user.id}?`
    );
    if (!confirmed) {
      return;
    }
    setSubmitStatus(null);
    try {
      const updated = await apiFetch<User>(`/v1/users/${user.id}`, {
        method: "DELETE",
        body: JSON.stringify({ reason })
      });
      setUsers((prev) =>
        prev.map((entry) => (entry.id === user.id ? updated : entry))
      );
      setSubmitStatus("User deleted.");
    } catch (err) {
      setSubmitStatus(err instanceof Error ? err.message : "Delete failed.");
    }
  };

  const loadMore = async () => {
    if (!nextCursor || loadingMore) {
      return;
    }
    setLoadingMore(true);
    try {
      await loadUsers({ preserve: true, cursor: nextCursor, append: true });
    } finally {
      setLoadingMore(false);
    }
  };

  const roleOptions = useMemo(() => ROLE_OPTIONS, []);

  return (
    <main>
      <div className="container stack">
        <div>
          <span className="tag">Admin</span>
          <h1>User Administration</h1>
          <p>Manage user accounts, roles, and status changes.</p>
        </div>

        <section className="card stack">
          <h2>Create User</h2>
          <div className="row">
            <input
              className="input"
              placeholder="Email"
              value={createForm.email}
              onChange={(event) =>
                setCreateForm((prev) => ({
                  ...prev,
                  email: event.target.value
                }))
              }
            />
            <input
              className="input"
              placeholder="Display name"
              value={createForm.displayName}
              onChange={(event) =>
                setCreateForm((prev) => ({
                  ...prev,
                  displayName: event.target.value
                }))
              }
            />
          </div>
          <div className="row">
            {roleOptions.map((role) => (
              <label className="badge" key={role}>
                <input
                  type="checkbox"
                  checked={createForm.roles.includes(role)}
                  onChange={(event) => {
                    setCreateForm((prev) => {
                      const nextRoles = new Set(prev.roles);
                      if (event.target.checked) {
                        nextRoles.add(role);
                      } else {
                        nextRoles.delete(role);
                      }
                      if (nextRoles.size === 0) {
                        nextRoles.add("reader");
                      }
                      return { ...prev, roles: Array.from(nextRoles) };
                    });
                  }}
                />
                {role}
              </label>
            ))}
          </div>
          <button className="button" onClick={submitCreateUser}>
            Create User
          </button>
        </section>

        <section className="card stack">
          <div className="row" style={{ justifyContent: "space-between" }}>
            <h2>Users</h2>
            <div className="row">
              <label className="badge">
                <input
                  type="checkbox"
                  checked={showDeleted}
                  onChange={(event) => setShowDeleted(event.target.checked)}
                />
                Show deleted
              </label>
              <button className="button secondary" onClick={() => void loadUsers()}>
                Refresh
              </button>
            </div>
          </div>
          {loading && <p>Loading users...</p>}
          {error && <p>{error}</p>}
          {!loading && users.length === 0 && <p>No users yet.</p>}
          {!loading && users.length > 0 && (
            <table className="table">
              <thead>
                <tr>
                  <th>User</th>
                  <th>Roles</th>
                  <th>Status</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {users.map((user) => {
                  const edit = roleEdits[user.id] || {
                    roles: new Set(user.roles || []),
                    reason: ""
                  };
                  return (
                    <tr key={user.id}>
                      <td>
                        <div>
                          <strong>{user.displayName || "(no name)"}</strong>
                        </div>
                        <div className="badge">{user.email || "no email"}</div>
                        <div className="badge">{user.id}</div>
                      </td>
                      <td>
                        <div className="stack">
                          <div className="row">
                            {roleOptions.map((role) => (
                              <label className="badge" key={`${user.id}-${role}`}>
                                <input
                                  type="checkbox"
                                  checked={edit.roles.has(role)}
                                  onChange={(event) => {
                                    setRoleEdits((prev) => {
                                      const next = new Set(edit.roles);
                                      if (event.target.checked) {
                                        next.add(role);
                                      } else {
                                        next.delete(role);
                                      }
                                      if (next.size === 0) {
                                        next.add("reader");
                                      }
                                      return {
                                        ...prev,
                                        [user.id]: {
                                          ...edit,
                                          roles: next
                                        }
                                      };
                                    });
                                  }}
                                />
                                {role}
                              </label>
                            ))}
                          </div>
                          <input
                            className="input"
                            placeholder="Reason for change"
                            value={edit.reason}
                            onChange={(event) =>
                              setRoleEdits((prev) => ({
                                ...prev,
                                [user.id]: {
                                  ...edit,
                                  reason: event.target.value
                                }
                              }))
                            }
                          />
                        </div>
                      </td>
                      <td>
                        <div className="badge">{user.status}</div>
                        <input
                          className="input"
                          placeholder="Status reason"
                          value={statusReasons[user.id] || ""}
                          onChange={(event) =>
                            setStatusReasons((prev) => ({
                              ...prev,
                              [user.id]: event.target.value
                            }))
                          }
                        />
                      </td>
                      <td>
                        <div className="stack">
                          <input
                            className="input"
                            placeholder="Delete reason"
                            value={deleteReasons[user.id] || ""}
                            onChange={(event) =>
                              setDeleteReasons((prev) => ({
                                ...prev,
                                [user.id]: event.target.value
                              }))
                            }
                          />
                          <button
                            className="button secondary"
                            onClick={() => updateRoles(user.id)}
                          >
                            Save Roles
                          </button>
                          <button
                            className="button"
                            onClick={() => toggleStatus(user)}
                            disabled={user.status === "deleted"}
                          >
                            {user.status === "active"
                              ? "Disable"
                              : "Enable"}
                          </button>
                          <button
                            className="button secondary"
                            onClick={() => deleteUser(user)}
                          >
                            Delete
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
          {!loading && nextCursor && (
            <button className="button secondary" onClick={loadMore} disabled={loadingMore}>
              {loadingMore ? "Loading..." : "Load more"}
            </button>
          )}
        </section>

        {submitStatus && <p>{submitStatus}</p>}
      </div>
    </main>
  );
}

'use client';
import { useEffect, useState } from 'react';
import { api } from '@/src/lib/api';
import type { AdminUser } from '@/src/lib/types';
import { useAuth } from '@/src/hooks/useAuth';

export default function AdminUsersPage() {
  const { token } = useAuth();
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [error, setError] = useState('');

  useEffect(() => {
    if (token) {
      api.admin.listUsers(token).then(setUsers).catch(e => setError(String(e)));
    }
  }, [token]);

  const remove = async (id: number) => {
    if (!confirm('Delete this user?')) return;
    try {
      await api.admin.deleteUser(id, token);
      setUsers(prev => prev.filter(u => u.id !== id));
    } catch (e) {
      setError(String(e));
    }
  };

  return (
    <div className="p-8 space-y-6">
      <h2 className="text-xl font-semibold text-slate-900">Users</h2>
      {error && (
        <p className="text-sm text-red-600 bg-red-50 border border-red-200 rounded-lg px-4 py-3">{error}</p>
      )}
      <div className="bg-white border border-slate-200 rounded-xl overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-slate-50 border-b border-slate-200">
            <tr>
              <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">ID</th>
              <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Username</th>
              <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Role</th>
              <th className="px-4 py-3" />
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {users.map(u => (
              <tr key={u.id} className="hover:bg-slate-50">
                <td className="px-4 py-3 text-slate-500 tabular-nums">{u.id}</td>
                <td className="px-4 py-3 font-medium text-slate-900">{u.username}</td>
                <td className="px-4 py-3">
                  <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${
                    u.role === 'ADMIN' ? 'bg-indigo-100 text-indigo-700' : 'bg-slate-100 text-slate-600'
                  }`}>{u.role}</span>
                </td>
                <td className="px-4 py-3 text-right">
                  <button
                    onClick={() => remove(u.id)}
                    className="text-xs text-red-600 hover:text-red-800 font-medium"
                  >
                    Delete
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

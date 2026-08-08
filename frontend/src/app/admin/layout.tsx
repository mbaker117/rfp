'use client';
import { usePathname } from 'next/navigation';
import Link from 'next/link';
import { useAuth } from '@/src/hooks/useAuth';

const navItems = [
  { href: '/admin/users', label: 'Users' },
  { href: '/admin/suppliers', label: 'Suppliers' },
  { href: '/admin/products', label: 'Products' },
  { href: '/admin/rfps', label: 'RFPs' },
];

export default function AdminLayout({ children }: { children: React.ReactNode }) {
  const { token } = useAuth();
  const pathname = usePathname();

  if (!token) return null;

  return (
    <div className="flex min-h-screen">
      {/* Sidebar */}
      <aside className="w-60 shrink-0 bg-slate-900 flex flex-col">
        <div className="px-5 py-5 border-b border-slate-700">
          <span className="text-sm font-semibold text-white">RFP Admin</span>
        </div>
        <nav className="flex-1 py-4 space-y-0.5">
          {navItems.map(item => {
            const active = pathname.startsWith(item.href);
            return (
              <Link
                key={item.href}
                href={item.href}
                className={`flex items-center px-5 py-2.5 text-sm transition-colors ${
                  active
                    ? 'border-l-2 border-indigo-500 bg-slate-800 text-white'
                    : 'text-slate-400 hover:text-white hover:bg-slate-800'
                }`}
              >
                {item.label}
              </Link>
            );
          })}
        </nav>
        <div className="px-5 py-4 border-t border-slate-700">
          <Link href="/" className="text-xs text-slate-500 hover:text-slate-300 transition-colors">
            ← Back to App
          </Link>
        </div>
      </aside>

      {/* Content */}
      <main className="flex-1 bg-slate-50 overflow-auto">
        {children}
      </main>
    </div>
  );
}

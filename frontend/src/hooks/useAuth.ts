'use client';
import { useEffect, useState } from 'react';
import { useRouter, usePathname } from 'next/navigation';

function decodeRole(token: string): string {
  try {
    const payload = JSON.parse(atob(token.split('.')[1]));
    return payload.role ?? 'USER';
  } catch {
    return 'USER';
  }
}

export function useAuth() {
  const router = useRouter();
  const pathname = usePathname();
  const [token, setToken] = useState('');
  const [role, setRole] = useState('USER');

  useEffect(() => {
    const stored = localStorage.getItem('token') ?? '';
    if (!stored) {
      router.replace(`/auth?next=${encodeURIComponent(pathname)}`);
    } else {
      setToken(stored);
      setRole(decodeRole(stored));
    }
  }, [pathname, router]);

  const logout = () => {
    localStorage.removeItem('token');
    router.replace('/auth');
  };

  return { token, role, logout };
}

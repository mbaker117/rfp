'use client';
import { useEffect, useState } from 'react';
import { useRouter, usePathname } from 'next/navigation';

export function useAuth() {
  const router = useRouter();
  const pathname = usePathname();
  const [token, setToken] = useState('');

  useEffect(() => {
    const stored = localStorage.getItem('token') ?? '';
    if (!stored) {
      router.replace(`/auth?next=${encodeURIComponent(pathname)}`);
    } else {
      setToken(stored);
    }
  }, [pathname, router]);

  return { token };
}

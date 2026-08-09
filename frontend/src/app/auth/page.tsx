'use client';
import { Suspense } from 'react';
import AuthForm from './AuthForm';

export default function AuthPage() {
  return (
    <Suspense>
      <AuthForm />
    </Suspense>
  );
}

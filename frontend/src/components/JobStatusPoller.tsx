'use client';
import { useEffect } from 'react';
import { api } from '@/src/lib/api';

interface Props {
  rfpId: number;
  token: string;
  onComplete: () => void;
  onError: (msg: string) => void;
}

export function JobStatusPoller({ rfpId, token, onComplete, onError }: Props) {
  useEffect(() => {
    const interval = setInterval(async () => {
      try {
        const report = await api.getReport(rfpId, token);
        if (report.status === 'done') {
          clearInterval(interval);
          onComplete();
        } else if (report.status === 'failed') {
          clearInterval(interval);
          onError('Matching failed');
        }
      } catch (e) {
        clearInterval(interval);
        onError(String(e));
      }
    }, 3000);

    return () => clearInterval(interval);
  }, [rfpId, token, onComplete, onError]);

  return <p className="text-gray-500 animate-pulse">Processing... please wait</p>;
}

'use client';
import { useRef } from 'react';

interface Props {
  onFile: (f: File) => void;
}

export function FileUpload({ onFile }: Props) {
  const ref = useRef<HTMLInputElement>(null);

  return (
    <div
      className="border-2 border-dashed border-gray-300 rounded-lg p-8 text-center cursor-pointer hover:border-blue-400 transition-colors"
      onClick={() => ref.current?.click()}
      onDragOver={e => e.preventDefault()}
      onDrop={e => {
        e.preventDefault();
        const f = e.dataTransfer.files[0];
        if (f) onFile(f);
      }}
    >
      <input
        ref={ref}
        type="file"
        accept=".pdf,.docx,.xlsx,.xls,.doc"
        className="hidden"
        onChange={e => {
          const f = e.target.files?.[0];
          if (f) onFile(f);
        }}
      />
      <p className="text-gray-500">Drop PDF, Word, or Excel here, or click to browse</p>
    </div>
  );
}

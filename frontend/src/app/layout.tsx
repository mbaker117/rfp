import type { Metadata } from 'next'

export const metadata: Metadata = {
  title: 'RFP Instrument Matching',
  description: 'Match RFP instrument requirements against company catalogs',
}

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  )
}

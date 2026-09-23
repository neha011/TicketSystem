/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  // The backend base URL is the only environment-specific value the UI needs.
  // It is read at build time and exposed to the browser bundle; it must never
  // carry credentials or secrets.
  env: {
    NEXT_PUBLIC_API_BASE_URL: process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080',
  },
};

export default nextConfig;

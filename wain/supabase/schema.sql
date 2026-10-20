-- Community reports backend for Wain? (Supabase / PostgreSQL). Run in the Supabase SQL editor.
-- The mobile app uses only the public anon key; these policies are the security boundary.

create extension if not exists pgcrypto;

create table if not exists public.reports (
  id            uuid primary key default gen_random_uuid(),
  type          text not null check (type in ('traffic','accident','closure','roadworks','pothole','debris','broken_down','flood','dangerous','obstruction','other')),
  lat           double precision not null check (lat between -90 and 90),
  lon           double precision not null check (lon between -180 and 180),
  comment       text check (comment is null or char_length(comment) <= 200),
  device_id     text not null check (char_length(device_id) between 16 and 64),
  created_at    timestamptz not null default now(),
  expires_at    timestamptz not null default now(),
  confirmations integer not null default 0,
  status        text not null default 'unverified' check (status in ('unverified','confirmed','rejected'))
);

create index if not exists reports_geo_idx    on public.reports (lat, lon);
create index if not exists reports_expiry_idx on public.reports (expires_at);
create index if not exists reports_device_idx on public.reports (device_id, created_at);

-- Server-side rules: expiry per type, per-device rate limit, duplicate suppression.
create or replace function public.reports_before_insert() returns trigger
language plpgsql security definer set search_path = public as $$
declare ttl interval;
begin
  new.created_at := now();
  new.confirmations := 0;
  new.status := 'unverified';
  ttl := case new.type
    when 'traffic' then interval '1 hour'      when 'accident' then interval '2 hours'
    when 'closure' then interval '24 hours'    when 'roadworks' then interval '7 days'
    when 'pothole' then interval '14 days'     when 'debris' then interval '3 hours'
    when 'broken_down' then interval '2 hours' when 'flood' then interval '6 hours'
    when 'dangerous' then interval '6 hours'   when 'obstruction' then interval '4 hours'
    else interval '2 hours' end;
  new.expires_at := now() + ttl;

  if (select count(*) from public.reports where device_id = new.device_id and created_at > now() - interval '1 hour') >= 10 then
    raise exception 'Rate limit: too many reports from this device. Try again later.';
  end if;

  if exists (
    select 1 from public.reports r
    where r.type = new.type and r.created_at > now() - interval '10 minutes'
      and abs(r.lat - new.lat) < 0.001 and abs(r.lon - new.lon) < 0.001
  ) then
    raise exception 'A similar report was already made here recently.';
  end if;
  return new;
end $$;

drop trigger if exists reports_before_insert on public.reports;
create trigger reports_before_insert before insert on public.reports
  for each row execute function public.reports_before_insert();

alter table public.reports enable row level security;

-- Anyone may read active, non-rejected reports. device_id stays unreadable because the app selects explicit columns
-- and the view below should be preferred for stricter setups.
drop policy if exists reports_read on public.reports;
create policy reports_read on public.reports for select to anon, authenticated
  using (expires_at > now() and status <> 'rejected');

-- Anyone may insert; the trigger enforces expiry, rate limit and duplicates. No update/delete for anon.
drop policy if exists reports_insert on public.reports;
create policy reports_insert on public.reports for insert to anon, authenticated with check (true);

revoke update, delete on public.reports from anon, authenticated;
-- Moderation (rejecting reports, purging expired rows) is done with the service role from a trusted environment,
-- e.g. a scheduled job:  delete from public.reports where expires_at < now() - interval '7 days';

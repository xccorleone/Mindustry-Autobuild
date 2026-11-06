# Safe drones: poly and mega avoid enemy fire while they work

Status: approved 2026-10-31, implemented on `feat/safe-drones`.

## Goal

Poly (tier 2) and mega (tier 3), the green support air units, keep working under the **rebuild** and
**repair** commands without flying into enemy fire. While working, if the unit can be attacked where it
is, it pauses, moves to a safe spot from which it can still do the same job, and resumes; if there is no
such spot it gives the job up for a while and flees. Assist and the other commands stay vanilla, other
units stay vanilla.

## How it plugs in

`UnitCommand.rebuildCommand` / `repairCommand` hold the factory (`controller`, a final field) that
`CommandAI` calls when a unit gets that command. At mod init the factory is wrapped (reflection, once):
poly and mega get `SafeBuilderAI` / `SafeRepairAI`, every other unit the original controller. Both are
subclasses of the vanilla AI, so plan choice, validation, following and idle retreat stay the game's;
they only take over where the unit flies and when it pauses. No new content: saves and multiplayer are
unaffected. A setting (`autobuild-safe-drones`, on by default, in the game's settings) turns the
behaviour off at once, also for units already out.

The AI runs where the game is simulated: singleplayer, or when you host. On someone else's server it
does nothing (the server runs the AI).

## Danger

Danger is a set of circles (`ThreatScan`, game side), each grown by the unit's half size:

- enemy turrets that target air: their range + 2 tiles (ammo not checked, on purpose);
- armed enemy units that target air: their range + 3 tiles (they move);
- hurt: when the unit loses health, a 6-tile circle where it was, for 5 seconds (covers sources the
  first two miss).

## Safe spots (`SafeSpots`, pure geometry, tested offline)

- **Work spot**: candidates on 3 rings × 16 directions around the work point (the plan, or the damaged
  building) inside the work range (build range, or the heal weapon range, both on the safe side); a
  candidate counts when it lies outside every circle and the straight flight to it does not enter a
  circle (a circle the unit is already in may only be left, not crossed deeper). The nearest wins.
- **Refuge**: the closest core if it is safe and reachable that way, else the nearest safe reachable
  point on growing rings around the unit, else any safe point, else straight away from the nearest
  danger.

## Behaviour (decided every 15 ticks, at once when the job changes or the unit is hurt)

- **Work**: safe here and within range: hold here (no drifting into danger) and work.
- **Relocate**: unsafe here, or out of range: fly to the work spot; work is paused while the unit
  stands in danger.
- **Flee**: no work spot: drop the job (a rebuild plan is not taken again, a building not repaired
  again, for 10 seconds) and fly to the refuge until the unit stands safe. Also when idle in danger.

The hold-position stance keeps vanilla behaviour. Repair picks the closest damaged building that is not
dropped.

## Tests

- `test/autobuild/SafeSpotsTest.java` in `./test.sh`: spots outside circles, paths around circles,
  leaving a circle, no spot when the work point is deep inside, refuge choice.
- Server: `abdrones` on a dedicated server places an enemy anti-air turret, a destroyed block and a
  damaged block next to it and a poly (rebuild) and a mega (repair); `abdronescount` reports the
  units' closest approach to the turret and the jobs done or dropped.

# EnergyGames plugin

Java/Paper port of `energy_games.sk`. Prebuilt jar: `dist/EnergyGames-1.0.0.jar`.

* Paper or Spigot **1.20 – 1.21.x**, Java 17+.
* Skript is optional. When it's installed, the plugin reads `{DragonGames}`, calls
  `getEnergyToken()`, `IsGem()` and `isMythical()`, and keeps `{EnergyGames}`,
  `{participant::<uuid>}`, `{timer.running}` and `{timer.running1}` updated for your other scripts.
* **Remove `energy_games.sk`** from `plugins/Skript/scripts` before using this, or the commands will clash.
  If other scripts call `busyHand()`, `getTracker()` or `getRuleRequest()`, drop in
  `energy_games_functions.sk` too.

## Commands
| Command | Permission |
|---|---|
| `/energygames start \| end \| tracker \| rule` (alias `/eg`) | `energygames.admin` (op) |
| `/energygames settoken` – save the item in your hand as the trade currency | `energygames.admin` |
| `/energygames cleartoken`, `/energygames reload` | `energygames.admin` |
| `/energygames trade` | `energygames.trade` (everyone) |
| `/glowing`, `/glowing-end`, `/double-energy`, `/endtimer` | `energygames.admin` |

Build: `mvn package` → `target/EnergyGames-1.0.0.jar`.

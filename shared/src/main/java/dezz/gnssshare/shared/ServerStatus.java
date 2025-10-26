/*
 * Copyright © 2025 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dezz.gnssshare.shared;

public enum ServerStatus {
    UNINITIALIZED,
    AWAITING_LOCATION,
    TRANSMITTING_LOCATION,
    LOCATION_STOPPED;

    // TODO: Remove in favor of localized strings usage
    @Override
    public String toString() {
        return switch (this) {
            case UNINITIALIZED -> "Uninitialized";
            case AWAITING_LOCATION -> "Waiting for location...";
            case TRANSMITTING_LOCATION -> "Transmitting location";
            case LOCATION_STOPPED -> "Location updates stopped";
        };
    }
}

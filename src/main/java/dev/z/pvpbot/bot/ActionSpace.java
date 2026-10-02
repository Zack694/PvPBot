package dev.z.pvpbot.bot;

/**
 * The bot's "muscles" — the exact same inputs a human player has:
 * W/A/S/D, sprint, jump, left click, and mouse movement. 72 discrete actions:
 * 9 move combos x 2 sprint x 2 jump x 2 attack. Aiming is continuous (AimController).
 */
public final class ActionSpace {

        public static final int MOVES = 9;
        public static final int COUNT = MOVES * 8;

        // move combo ids
        public static final int M_NONE = 0, M_W = 1, M_S = 2, M_A = 3, M_D = 4, M_WA = 5, M_WD = 6, M_SA = 7, M_SD = 8;

        public static final int A_ATTACK = 1, A_JUMP = 2, A_SPRINT = 4;

        public static int encode(int move, boolean sprint, boolean jump, boolean attack) {
                int bits = move * 8;
                if (sprint) bits |= A_SPRINT;
                if (jump) bits |= A_JUMP;
                if (attack) bits |= A_ATTACK;
                return bits;
        }

        public static int moveOf(int action) {
                // AUDIT FIX: the old `(action >> 3) & 7` masked move 8 (M_SD) down
                // to 0 — every DQN pick of the M_SD family (actions 64-71) executed
                // as IDLE, so the back-right diagonal could never physically happen.
                // 9 moves x 8 bits = 72 actions; move = action / 8 = 0..8 exactly.
                int m = action >> 3;
                return Math.max(0, Math.min(M_SD, m));
        }

        public static boolean sprintOf(int action) {
                return (action & A_SPRINT) != 0;
        }

        public static boolean jumpOf(int action) {
                return (action & A_JUMP) != 0;
        }

        public static boolean attackOf(int action) {
                return (action & A_ATTACK) != 0;
        }

        public static String describe(int action) {
                String[] names = {"idle", "W", "S", "A", "D", "WA", "WD", "SA", "SD"};
                StringBuilder sb = new StringBuilder(names[moveOf(action)]);
                if (sprintOf(action)) sb.append("+sprint");
                if (jumpOf(action)) sb.append("+jump");
                if (attackOf(action)) sb.append("+atk");
                return sb.toString();
        }

        private ActionSpace() {}
}

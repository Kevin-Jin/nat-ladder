package in.kevinj.natladder.common.util;

public class CliHelper {
	public static String tryGet(String[] args, int index, String def) {
		if (args.length > index)
			return args[index];
		else
			return def;
	}

	public static int tryParse(String[] args, int index, int def) {
		if (args.length > index) {
			try {
				return Integer.parseInt(args[index]);
			} catch (NumberFormatException e) {
				return def;
			}
		} else {
			return def;
		}
	}
}

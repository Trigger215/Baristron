package chrislo27.bot.util;

import java.util.ArrayList;
import java.util.Comparator;

public class BpmCalc {

	private static final Comparator<ChangesTempo> comparator = new Comparator<ChangesTempo>() {

		@Override
		public int compare(ChangesTempo o1, ChangesTempo o2) {
			if (o1.getBeat() < o2.getBeat()) return -1;
			if (o1.getBeat() > o2.getBeat()) return 1;

			return 0;
		}
	};
	private static ArrayList<ChangesTempo> tmpArray = new ArrayList<>();

	public static ArrayList<ChangesTempo> grabFromObjects(ArrayList<? extends Object> objs) {
		tmpArray.clear();

		for (int i = 0; i < objs.size(); i++) {
			if (objs.get(i) instanceof ChangesTempo) {
				tmpArray.add((ChangesTempo) objs.get(i));
			}
		}

		tmpArray.sort(comparator);

		return tmpArray;
	}

	public static float calcSecondsMultiBpm(float beat, float baseBpm,
			ArrayList<ChangesTempo> array) {
		if (array.size() == 0) return getSecFromBeat(beat, baseBpm);

		array.sort(comparator);

		final float start = Math.min(beat, 0);
		float currentSec = getSecFromBeat(start, baseBpm);
		float currentBeat = start;
		float currentTempo = baseBpm;

		for (int i = 0; i < array.size(); i++) {
			ChangesTempo current = array.get(i);

			if (current.getBeat() >= beat) break;

			float beatDiff = current.getBeat() - currentBeat;

			currentSec += getSecFromBeat(beatDiff, currentTempo);
			currentBeat = current.getBeat();
			currentTempo = current.getNewBPM();
		}

		return currentSec + getSecFromBeat(beat - currentBeat, currentTempo);
	}

	public static float calcBeatsMultiBpm(float seconds, float baseBpm,
			ArrayList<ChangesTempo> array) {
		if (array.size() == 0) return getBeatFromSec(seconds, baseBpm);

		array.sort(comparator);

		final float start = Math.min(seconds, 0);
		float currentSec = getSecFromBeat(0, baseBpm);
		float currentBeat = start;
		float currentTempo = baseBpm;

		for (int i = 0; i < array.size(); i++) {
			ChangesTempo current = array.get(i);

			float beatDiff = current.getBeat() - currentBeat;
			float newCurrentSec = currentSec + getSecFromBeat(beatDiff, currentTempo);

			if (newCurrentSec >= seconds) break;

			currentSec = newCurrentSec;
			currentBeat = current.getBeat();
			currentTempo = current.getNewBPM();
		}

		return currentBeat + getBeatFromSec(seconds - currentSec, currentTempo);
	}

	public static float getBeatFromSec(float sec, float bpm) {
		return sec * (bpm * PreDiv.SIXTIETH);
	}

	public static float getSecFromBeat(float beat, float bpm) {
		return beat / (bpm * PreDiv.SIXTIETH);
	}

	public static float getPitchFromBpm(float actualBpm, float bpm) {
		return bpm / actualBpm;
	}

	public static interface ChangesTempo {

		public float getNewBPM();

		public float getBeat();

	}
}

/*******************************************************************************
 * Copyright (c) 2010, 2012 Institute for Dutch Lexicology
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package nl.inl.blacklab.search.lucene;

import java.io.IOException;
import java.util.Collection;

import nl.inl.blacklab.search.Span;

import org.apache.lucene.search.spans.Spans;

/**
 * Combines two Spans using AND. Note that this means that only matches with the same document id,
 * the same start and the same end positions will be kept.
 */
class SpansAnd extends BLSpans {
	/** The two sets of hits to combine */
	private BLSpans[] spans = new BLSpans[2];

	/** -1 = not started, NO_MORE_DOCS = done, otherwise = docID */
	private int currentDoc[] = new int[2];

	/** -1 = not started, NO_MORE_POSITIONS = done, otherwise = start position */
	private int currentStart[] = new int[2];

	private boolean alreadyAtFirstMatch = false;

	public SpansAnd(Spans leftClause, Spans rightClause) {
		spans[0] = BLSpansWrapper.optWrapSort(leftClause);
		spans[1] = BLSpansWrapper.optWrapSort(rightClause);
		currentDoc[0] = currentDoc[1] = -1;
		currentStart[0] = currentStart[1] = -1;
	}

	@Override
	public int docID() {
		if (currentDoc[1] == NO_MORE_DOCS)
			return NO_MORE_DOCS;
		return currentDoc[0];
	}

	@Override
	public int startPosition() {
		if (alreadyAtFirstMatch)
			return -1; // .nextStartPosition() not called yet
		if (currentStart[1] == NO_MORE_POSITIONS)
			return NO_MORE_POSITIONS;
		return currentStart[0];
	}

	@Override
	public int endPosition() {
		if (alreadyAtFirstMatch)
			return -1; // .nextStartPosition() not called yet
		if (currentStart[0] == NO_MORE_POSITIONS || currentStart[1] == NO_MORE_POSITIONS)
			return NO_MORE_POSITIONS;
		return spans[0].endPosition();
	}

	@Override
	public int nextDoc() throws IOException {
		alreadyAtFirstMatch = false;
		if (currentDoc[0] == NO_MORE_DOCS || currentDoc[1] == NO_MORE_DOCS)
			return NO_MORE_DOCS;
		int laggingSpans = currentDoc[0] < currentDoc[1] ? 0 : 1;
		catchUpDoc(laggingSpans);
		return synchronizeDoc();
	}

	@Override
	public int nextStartPosition() throws IOException {
		if (alreadyAtFirstMatch) {
			alreadyAtFirstMatch = false;
			return currentStart[0];
		}
		if (currentDoc[0] == NO_MORE_DOCS || currentDoc[1] == NO_MORE_DOCS)
			return NO_MORE_POSITIONS;
		// Waren we al klaar?
		if (currentStart[0] == NO_MORE_POSITIONS || currentStart[1] == NO_MORE_POSITIONS)
			return NO_MORE_POSITIONS;

		// Draai beide Spans door
		currentStart[0] = spans[0].nextStartPosition();
		currentStart[1] = spans[1].nextStartPosition();

		return synchronizePosition();
	}

	@Override
	public int advanceStartPosition(int target) throws IOException {
		if (alreadyAtFirstMatch) {
			alreadyAtFirstMatch = false;
			if (currentStart[0] >= target)
				return currentStart[0];
		}
		for (int i = 0; i < 2; i++) {
			currentStart[i] = spans[i].advanceStartPosition(target);
		}
		return synchronizePosition();
	}

	private int synchronizePosition() throws IOException {
		// Synchronise spans
		while (true) {

			if (currentStart[0] == NO_MORE_POSITIONS || currentStart[1] == NO_MORE_POSITIONS)
				return NO_MORE_POSITIONS;

			// Synch at match start level
			if (currentStart[0] == -1 && currentStart[1] == -1 || currentStart[0] != currentStart[1]) {
				int laggingSpans = currentStart[0] < currentStart[1] ? 0 : 1;
				catchUpMatchStart(laggingSpans);
				continue; // restart synching
			}

			// Synch at match end level
			if (spans[0].endPosition() != spans[1].endPosition()) {
				int laggingSpans = spans[0].endPosition() < spans[1].endPosition() ? 0 : 1;
				catchUpMatchEnd(laggingSpans);
				continue; // restart synching
			}

			// Are we done?
			if (currentStart[0] == NO_MORE_POSITIONS || currentStart[1] == NO_MORE_POSITIONS) {
				// Yes, one of the Spans was exhausted
				return NO_MORE_POSITIONS;
			}

			// No, we are synched on a new hit
			return currentStart[0];
		}

	}

	/**
	 * Put both spans in the same doc.
	 * @return the doc id if succesful, or NO_MORE_DOCS if we're done
	 * @throws IOException
	 */
	private int synchronizeDoc() throws IOException {
		while (true) {

			// Are we done?
			if (currentDoc[0] == NO_MORE_DOCS || currentDoc[1] == NO_MORE_DOCS) {
				// Yes, one of the Spans was exhausted
				return NO_MORE_DOCS;
			}

			// Synch at document level
			if (currentDoc[0] != currentDoc[1]) {
				int laggingSpans = currentDoc[0] < currentDoc[1] ? 0 : 1;
				catchUpDoc(laggingSpans);
				continue;
			}

			if (synchronizePosition() == NO_MORE_POSITIONS) {
				// This doc doesn't match; try next doc.
				currentDoc[0] = spans[0].nextDoc();
				currentStart[0] = -1;
				if (currentDoc[0] == NO_MORE_DOCS)
					return NO_MORE_DOCS;
				continue;
			}

			// We are synched on a new hit
			alreadyAtFirstMatch = true;
			return currentDoc[0];
		}

	}

	private void catchUpDoc(int laggingSpans) throws IOException {
		currentDoc[laggingSpans] = spans[laggingSpans].advance(currentDoc[1 - laggingSpans]);
		currentStart[laggingSpans] = -1;
	}

	private void catchUpMatchStart(int laggingSpans) throws IOException {
		int catchUpTo = currentStart[1 - laggingSpans];
		if (currentStart[laggingSpans] != NO_MORE_POSITIONS && currentStart[laggingSpans] < catchUpTo || currentStart[laggingSpans] == -1) {
			currentStart[laggingSpans] = spans[laggingSpans].advanceStartPosition(catchUpTo);
		}
//		while (currentStart[laggingSpans] != NO_MORE_POSITIONS && currentStart[laggingSpans] < catchUpTo || currentStart[laggingSpans] == -1) {
//			currentStart[laggingSpans] = spans[laggingSpans].nextStartPosition();
//		}
	}

	private void catchUpMatchEnd(int laggingSpans) throws IOException {
		int start = currentStart[laggingSpans];
		int catchUpTo = spans[1 - laggingSpans].endPosition();
		while (currentStart[laggingSpans] == start && spans[laggingSpans].endPosition() < catchUpTo || currentStart[laggingSpans] == -1) {
			currentStart[laggingSpans] = spans[laggingSpans].nextStartPosition();
		}
	}

	@Override
	public int advance(int doc) throws IOException {
		// Skip beiden tot aan doc
		currentDoc[0] = spans[0].advance(doc);
		currentStart[0] = -1; // not started yet
		currentDoc[1] = spans[1].advance(doc);
		currentStart[1] = -1; // not started yet
		return synchronizeDoc();
	}

	@Override
	public String toString() {
		return "AndSpans(" + spans[0] + ", " + spans[1] + ")";
	}

	@Override
	public boolean hitsAllSameLength() {
		return spans[0].hitsAllSameLength() || spans[1].hitsAllSameLength();
	}

	@Override
	public int hitsLength() {
		if (spans[0].hitsAllSameLength())
			return spans[0].hitsLength();
		return spans[1].hitsLength();
	}

	@Override
	public boolean hitsHaveUniqueStart() {
		return spans[0].hitsHaveUniqueStart() || spans[1].hitsHaveUniqueStart();
	}

	@Override
	public boolean hitsHaveUniqueEnd() {
		return spans[0].hitsHaveUniqueEnd() || spans[1].hitsHaveUniqueEnd();
	}

	@Override
	public boolean hitsAreUnique() {
		return spans[0].hitsAreUnique() || spans[1].hitsAreUnique();
	}

	@Override
	public void passHitQueryContextToClauses(HitQueryContext context) {
		spans[0].setHitQueryContext(context);
		spans[1].setHitQueryContext(context);
	}

	@Override
	public void getCapturedGroups(Span[] capturedGroups) {
		if (!childClausesCaptureGroups)
			return;
		spans[0].getCapturedGroups(capturedGroups);
		spans[1].getCapturedGroups(capturedGroups);
	}

	@Override
	public Collection<byte[]> getPayload() throws IOException {
		Collection<byte[]> payload = spans[0].getPayload();
		if (payload == null)
			payload = spans[1].getPayload();
		return payload;
	}

	@Override
	public boolean isPayloadAvailable() throws IOException {
		return spans[0].isPayloadAvailable() || spans[1].isPayloadAvailable();
	}



}

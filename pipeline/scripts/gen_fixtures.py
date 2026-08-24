"""One-time generator for the P1.2 awkward-case MusicXML fixtures (plan §8.2.13).

The generated .musicxml files are COMMITTED — tests never generate fixtures at
run time. Run from the repo root:

    pipeline/.venv/bin/python pipeline/scripts/gen_fixtures.py
"""

from __future__ import annotations

from pathlib import Path

FIXTURES = Path(__file__).resolve().parents[1] / "tests" / "fixtures"
BAD = Path(__file__).resolve().parents[1] / "tests" / "bad"

HEADER = '<?xml version="1.0" encoding="UTF-8"?>\n'

P1 = '    <score-part id="P1"><part-name>Piano</part-name></score-part>'
P2 = '    <score-part id="P2"><part-name>Piano</part-name></score-part>'


def q(pitch: str, dur: int, *, voice: int = 1, extra: str = "") -> str:
    return (
        f"      <note><pitch><step>{pitch}</step><octave>{pitch[-1]}</octave></pitch>"
        f"<duration>{dur}</duration><voice>{voice}</voice><type>quarter</type>{extra}</note>"
    )


def scale_fixture() -> str:
    return f"""<!-- P1.2 golden fixture: mid-song key change. C major -> G major at
     measure 5 (atBeat 16); F# notes carry display-only accidentals. -->
<score-partwise version="4.0">
  <part-list>
{P1}
  </part-list>
  <part id="P1">
    <measure number="1">
      <attributes>
        <divisions>12</divisions>
        <key><fifths>0</fifths></key>
        <time><beats>4</beats><beat-type>4</beat-type></time>
        <clef><sign>G</sign><line>2</line></clef>
      </attributes>
      <direction placement="above"><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>100</per-minute></metronome></direction-type></direction>
      <note><pitch><step>C</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>D</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>E</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>F</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
    <measure number="2">
      <note><pitch><step>G</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>A</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>B</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>C</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
    <measure number="3">
      <note><pitch><step>D</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>E</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>F</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>G</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
    <measure number="4">
      <note><pitch><step>A</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>G</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>F</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>E</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
    <measure number="5">
      <attributes>
        <key><fifths>1</fifths></key>
      </attributes>
      <note><pitch><step>D</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>F</step><alter>1</alter><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type><accidental>sharp</accidental></note>
      <note><pitch><step>G</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>A</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
    <measure number="6">
      <note><pitch><step>B</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>A</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>G</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>F</step><alter>1</alter><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type><accidental>sharp</accidental></note>
    </measure>
    <measure number="7">
      <note><pitch><step>E</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>D</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>C</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>B</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
    <measure number="8">
      <note><pitch><step>A</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>G</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>F</step><alter>1</alter><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type><accidental>sharp</accidental></note>
      <note><pitch><step>G</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
  </part>
</score-partwise>
"""


def triplets_fixture() -> str:
    return f"""<!-- P1.2 golden fixture: triplet eighths (exact fractional beats).
     divisions=12; triplet eighth = 4 divisions = 1/3 beat. -->
<score-partwise version="4.0">
  <part-list>
{P1}
  </part-list>
  <part id="P1">
    <measure number="1">
      <attributes>
        <divisions>12</divisions>
        <key><fifths>0</fifths></key>
        <time><beats>4</beats><beat-type>4</beat-type></time>
        <clef><sign>G</sign><line>2</line></clef>
      </attributes>
      <direction placement="above"><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>120</per-minute></metronome></direction-type></direction>
      <note><pitch><step>C</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>E</step><octave>4</octave></pitch><duration>4</duration><voice>1</voice><type>eighth</type><time-modification><actual-notes>3</actual-notes><normal-notes>2</normal-notes></time-modification></note>
      <note><pitch><step>F</step><octave>4</octave></pitch><duration>4</duration><voice>1</voice><type>eighth</type><time-modification><actual-notes>3</actual-notes><normal-notes>2</normal-notes></time-modification></note>
      <note><pitch><step>G</step><octave>4</octave></pitch><duration>4</duration><voice>1</voice><type>eighth</type><time-modification><actual-notes>3</actual-notes><normal-notes>2</normal-notes></time-modification></note>
      <note><pitch><step>A</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>B</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
    <measure number="2">
      <note><pitch><step>C</step><octave>5</octave></pitch><duration>4</duration><voice>1</voice><type>eighth</type><time-modification><actual-notes>3</actual-notes><normal-notes>2</normal-notes></time-modification></note>
      <note><pitch><step>B</step><octave>4</octave></pitch><duration>4</duration><voice>1</voice><type>eighth</type><time-modification><actual-notes>3</actual-notes><normal-notes>2</normal-notes></time-modification></note>
      <note><pitch><step>A</step><octave>4</octave></pitch><duration>4</duration><voice>1</voice><type>eighth</type><time-modification><actual-notes>3</actual-notes><normal-notes>2</normal-notes></time-modification></note>
      <note><pitch><step>G</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>F</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>E</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
    <measure number="3">
      <note><pitch><step>D</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>E</step><octave>4</octave></pitch><duration>4</duration><voice>1</voice><type>eighth</type><time-modification><actual-notes>3</actual-notes><normal-notes>2</normal-notes></time-modification></note>
      <note><pitch><step>F</step><octave>4</octave></pitch><duration>4</duration><voice>1</voice><type>eighth</type><time-modification><actual-notes>3</actual-notes><normal-notes>2</normal-notes></time-modification></note>
      <note><pitch><step>G</step><octave>4</octave></pitch><duration>4</duration><voice>1</voice><type>eighth</type><time-modification><actual-notes>3</actual-notes><normal-notes>2</normal-notes></time-modification></note>
      <note><pitch><step>A</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>B</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
    <measure number="4">
      <note><pitch><step>C</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>B</step><octave>4</octave></pitch><duration>4</duration><voice>1</voice><type>eighth</type><time-modification><actual-notes>3</actual-notes><normal-notes>2</normal-notes></time-modification></note>
      <note><pitch><step>A</step><octave>4</octave></pitch><duration>4</duration><voice>1</voice><type>eighth</type><time-modification><actual-notes>3</actual-notes><normal-notes>2</normal-notes></time-modification></note>
      <note><pitch><step>G</step><octave>4</octave></pitch><duration>4</duration><voice>1</voice><type>eighth</type><time-modification><actual-notes>3</actual-notes><normal-notes>2</normal-notes></time-modification></note>
      <note><pitch><step>F</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>E</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
  </part>
</score-partwise>
"""


def six_eight_fixture() -> str:
    return f"""<!-- P1.2 golden fixture: 6/8. Beat = quarter (spec §2), so a 6/8
     measure is 3 beats and an eighth is 0.5 beats. -->
<score-partwise version="4.0">
  <part-list>
{P1}
  </part-list>
  <part id="P1">
    <measure number="1">
      <attributes>
        <divisions>12</divisions>
        <key><fifths>0</fifths></key>
        <time><beats>6</beats><beat-type>8</beat-type></time>
        <clef><sign>G</sign><line>2</line></clef>
      </attributes>
      <direction placement="above"><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>120</per-minute></metronome></direction-type></direction>
      <note><pitch><step>C</step><octave>4</octave></pitch><duration>6</duration><voice>1</voice><type>eighth</type></note>
      <note><pitch><step>E</step><octave>4</octave></pitch><duration>6</duration><voice>1</voice><type>eighth</type></note>
      <note><pitch><step>G</step><octave>4</octave></pitch><duration>6</duration><voice>1</voice><type>eighth</type></note>
      <note><pitch><step>C</step><octave>5</octave></pitch><duration>6</duration><voice>1</voice><type>eighth</type></note>
      <note><pitch><step>G</step><octave>4</octave></pitch><duration>6</duration><voice>1</voice><type>eighth</type></note>
      <note><pitch><step>E</step><octave>4</octave></pitch><duration>6</duration><voice>1</voice><type>eighth</type></note>
    </measure>
    <measure number="2">
      <note><pitch><step>F</step><octave>4</octave></pitch><duration>6</duration><voice>1</voice><type>eighth</type></note>
      <note><pitch><step>A</step><octave>4</octave></pitch><duration>6</duration><voice>1</voice><type>eighth</type></note>
      <note><pitch><step>C</step><octave>5</octave></pitch><duration>6</duration><voice>1</voice><type>eighth</type></note>
      <note><pitch><step>F</step><octave>5</octave></pitch><duration>6</duration><voice>1</voice><type>eighth</type></note>
      <note><pitch><step>C</step><octave>5</octave></pitch><duration>6</duration><voice>1</voice><type>eighth</type></note>
      <note><pitch><step>A</step><octave>4</octave></pitch><duration>6</duration><voice>1</voice><type>eighth</type></note>
    </measure>
    <measure number="3">
      <note><pitch><step>G</step><octave>4</octave></pitch><duration>6</duration><voice>1</voice><type>eighth</type></note>
      <note><pitch><step>B</step><octave>4</octave></pitch><duration>6</duration><voice>1</voice><type>eighth</type></note>
      <note><pitch><step>D</step><octave>5</octave></pitch><duration>6</duration><voice>1</voice><type>eighth</type></note>
      <note><pitch><step>G</step><octave>5</octave></pitch><duration>6</duration><voice>1</voice><type>eighth</type></note>
      <note><pitch><step>D</step><octave>5</octave></pitch><duration>6</duration><voice>1</voice><type>eighth</type></note>
      <note><pitch><step>B</step><octave>4</octave></pitch><duration>6</duration><voice>1</voice><type>eighth</type></note>
    </measure>
    <measure number="4">
      <note><pitch><step>C</step><octave>5</octave></pitch><duration>6</duration><voice>1</voice><type>eighth</type></note>
      <note><pitch><step>G</step><octave>4</octave></pitch><duration>6</duration><voice>1</voice><type>eighth</type></note>
      <note><pitch><step>E</step><octave>4</octave></pitch><duration>6</duration><voice>1</voice><type>eighth</type></note>
      <note><pitch><step>C</step><octave>4</octave></pitch><duration>6</duration><voice>1</voice><type>eighth</type></note>
      <note><pitch><step>E</step><octave>4</octave></pitch><duration>6</duration><voice>1</voice><type>eighth</type></note>
      <note><pitch><step>G</step><octave>4</octave></pitch><duration>6</duration><voice>1</voice><type>eighth</type></note>
    </measure>
  </part>
</score-partwise>
"""


def ties_fixture() -> str:
    return f"""<!-- P1.2 golden fixture: a tie crossing the M4/M5 boundary. The
     chunker must never cut between measures 4 and 5. -->
<score-partwise version="4.0">
  <part-list>
{P1}
  </part-list>
  <part id="P1">
    <measure number="1">
      <attributes>
        <divisions>12</divisions>
        <key><fifths>0</fifths></key>
        <time><beats>4</beats><beat-type>4</beat-type></time>
        <clef><sign>G</sign><line>2</line></clef>
      </attributes>
      <direction placement="above"><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>120</per-minute></metronome></direction-type></direction>
      <note><pitch><step>C</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>D</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>E</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>F</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
    <measure number="2">
      <note><pitch><step>G</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>A</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>B</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>C</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
    <measure number="3">
      <note><pitch><step>D</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>E</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>F</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>G</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
    <measure number="4">
      <note><pitch><step>A</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>G</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>F</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>E</step><octave>5</octave></pitch><duration>12</duration><tie type="start"/><voice>1</voice><type>quarter</type><notations><tied type="start"/></notations></note>
    </measure>
    <measure number="5">
      <note><pitch><step>E</step><octave>5</octave></pitch><duration>12</duration><tie type="stop"/><voice>1</voice><type>quarter</type><notations><tied type="stop"/></notations></note>
      <note><pitch><step>D</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>C</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>B</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
    <measure number="6">
      <note><pitch><step>B</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>A</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>G</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>F</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
    <measure number="7">
      <note><pitch><step>E</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>D</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>C</step><octave>4</octave></pitch><duration>24</duration><voice>1</voice><type>half</type></note>
    </measure>
    <measure number="8">
      <rest><duration>48</duration><voice>1</voice><type>whole</type></rest>
    </measure>
  </part>
</score-partwise>
"""


def repeats_fixture() -> str:
    return f"""<!-- P1.2 golden fixture: repeat + volta endings. M1, then
     |: M2 M3 :| (first ending) then M4 M5 (second ending). Playback order:
     M1 M2 M3 M4 M5. The repeatMap records the form. -->
<score-partwise version="4.0">
  <part-list>
{P1}
  </part-list>
  <part id="P1">
    <measure number="1">
      <attributes>
        <divisions>12</divisions>
        <key><fifths>0</fifths></key>
        <time><beats>4</beats><beat-type>4</beat-type></time>
        <clef><sign>G</sign><line>2</line></clef>
      </attributes>
      <direction placement="above"><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>120</per-minute></metronome></direction-type></direction>
      <note><pitch><step>C</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>D</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>E</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>F</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
    <measure number="2">
      <barline location="left"><repeat direction="forward"/></barline>
      <note><pitch><step>G</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>A</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>B</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>C</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <barline location="right"><ending number="1" type="start"/></barline>
    </measure>
    <measure number="3">
      <note><pitch><step>D</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>C</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>B</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>A</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <barline location="right"><ending number="1" type="stop"/></barline>
      <barline location="right"><repeat direction="backward" times="2"/></barline>
    </measure>
    <measure number="4">
      <note><pitch><step>G</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>F</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>E</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>D</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <barline location="right"><ending number="2" type="start"/></barline>
    </measure>
    <measure number="5">
      <note><pitch><step>C</step><octave>4</octave></pitch><duration>48</duration><voice>1</voice><type>whole</type></note>
      <barline location="right"><ending number="2" type="stop"/></barline>
    </measure>
  </part>
</score-partwise>
"""


def grace_ornaments_fixture() -> str:
    return f"""<!-- P1.2 golden fixture: grace notes, trill, mordent. The trill
     (whole C5) expands into 4 sixteenths (0.25 each); the mordent (half B4)
     into 3 notes; the grace D5 lands just before the E5. -->
<score-partwise version="4.0">
  <part-list>
{P1}
  </part-list>
  <part id="P1">
    <measure number="1">
      <attributes>
        <divisions>12</divisions>
        <key><fifths>0</fifths></key>
        <time><beats>4</beats><beat-type>4</beat-type></time>
        <clef><sign>G</sign><line>2</line></clef>
      </attributes>
      <direction placement="above"><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>120</per-minute></metronome></direction-type></direction>
      <note>
        <grace/>
        <pitch><step>D</step><octave>5</octave></pitch>
        <voice>1</voice>
        <type>eighth</type>
      </note>
      <note><pitch><step>E</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>F</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>G</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>A</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
    <measure number="2">
      <note><pitch><step>C</step><octave>5</octave></pitch><duration>48</duration><voice>1</voice><type>whole</type><notations><ornaments><trill-mark/></ornaments></notations></note>
    </measure>
    <measure number="3">
      <note><pitch><step>B</step><octave>4</octave></pitch><duration>24</duration><voice>1</voice><type>half</type><notations><ornaments><mordent/></ornaments></notations></note>
      <note><pitch><step>C</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>D</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
    <measure number="4">
      <note><pitch><step>G</step><octave>4</octave></pitch><duration>48</duration><voice>1</voice><type>whole</type></note>
    </measure>
  </part>
</score-partwise>
"""


def two_hands_fixture() -> str:
    return f"""<!-- P1.2 golden fixture: two-staff piano (RH melody, LH roots).
     Exercises hand assignment, the audio stems, and the full pack shape. -->
<score-partwise version="4.0">
  <part-list>
{P1}
{P2}
  </part-list>
  <part id="P1">
    <measure number="1">
      <attributes>
        <divisions>12</divisions>
        <key><fifths>0</fifths></key>
        <time><beats>4</beats><beat-type>4</beat-type></time>
        <clef><sign>G</sign><line>2</line></clef>
      </attributes>
      <direction placement="above"><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>120</per-minute></metronome></direction-type></direction>
      <note><pitch><step>C</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>D</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>E</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>F</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
    <measure number="2">
      <note><pitch><step>G</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>A</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>B</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>C</step><octave>6</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
    <measure number="3">
      <note><pitch><step>D</step><octave>6</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>C</step><octave>6</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>B</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>A</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
    <measure number="4">
      <note><pitch><step>G</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>F</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>E</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>D</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
    <measure number="5">
      <note><pitch><step>C</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>D</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>E</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>F</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
    <measure number="6">
      <note><pitch><step>G</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>A</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>B</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>C</step><octave>6</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
    <measure number="7">
      <note><pitch><step>D</step><octave>6</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>C</step><octave>6</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>B</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>A</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
    <measure number="8">
      <note><pitch><step>G</step><octave>5</octave></pitch><duration>48</duration><voice>1</voice><type>whole</type></note>
    </measure>
  </part>
  <part id="P2">
    <measure number="1">
      <attributes>
        <divisions>12</divisions>
        <key><fifths>0</fifths></key>
        <time><beats>4</beats><beat-type>4</beat-type></time>
        <clef><sign>F</sign><line>4</line></clef>
      </attributes>
      <note><pitch><step>C</step><octave>3</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>G</step><octave>3</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>C</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>G</step><octave>3</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
    <measure number="2">
      <note><pitch><step>C</step><octave>3</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>G</step><octave>3</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>C</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>G</step><octave>3</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
    <measure number="3">
      <note><pitch><step>F</step><octave>3</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>C</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>F</step><octave>3</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>A</step><octave>3</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
    <measure number="4">
      <note><pitch><step>G</step><octave>3</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>D</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>G</step><octave>3</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>B</step><octave>3</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
    <measure number="5">
      <note><pitch><step>C</step><octave>3</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>G</step><octave>3</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>C</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>G</step><octave>3</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
    <measure number="6">
      <note><pitch><step>C</step><octave>3</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>G</step><octave>3</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>C</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>G</step><octave>3</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
    <measure number="7">
      <note><pitch><step>F</step><octave>3</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>C</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>F</step><octave>3</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>A</step><octave>3</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
    <measure number="8">
      <note><pitch><step>G</step><octave>3</octave></pitch><duration>48</duration><voice>1</voice><type>whole</type></note>
    </measure>
  </part>
</score-partwise>
"""


def multi_voice_fixture() -> str:
    return f"""<!-- P1.2 golden fixture: two voices on one staff. Voice 1 carries
     C4, G4 (half), E4; voice 2 carries D4, F4, A4 (half). The normalization
     renumbers voices canonically. -->
<score-partwise version="4.0">
  <part-list>
{P1}
  </part-list>
  <part id="P1">
    <measure number="1">
      <attributes>
        <divisions>12</divisions>
        <key><fifths>0</fifths></key>
        <time><beats>4</beats><beat-type>4</beat-type></time>
        <clef><sign>G</sign><line>2</line></clef>
      </attributes>
      <direction placement="above"><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>120</per-minute></metronome></direction-type></direction>
      <note><pitch><step>C</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>G</step><octave>4</octave></pitch><duration>24</duration><voice>1</voice><type>half</type></note>
      <note><pitch><step>E</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <backup><duration>48</duration></backup>
      <note><pitch><step>D</step><octave>4</octave></pitch><duration>12</duration><voice>2</voice><type>quarter</type></note>
      <note><pitch><step>F</step><octave>4</octave></pitch><duration>12</duration><voice>2</voice><type>quarter</type></note>
      <note><pitch><step>A</step><octave>4</octave></pitch><duration>24</duration><voice>2</voice><type>half</type></note>
    </measure>
    <measure number="2">
      <note><pitch><step>F</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>E</step><octave>4</octave></pitch><duration>24</duration><voice>1</voice><type>half</type></note>
      <note><pitch><step>D</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <backup><duration>48</duration></backup>
      <note><pitch><step>A</step><octave>4</octave></pitch><duration>12</duration><voice>2</voice><type>quarter</type></note>
      <note><pitch><step>G</step><octave>4</octave></pitch><duration>12</duration><voice>2</voice><type>quarter</type></note>
      <note><pitch><step>F</step><octave>4</octave></pitch><duration>24</duration><voice>2</voice><type>half</type></note>
    </measure>
  </part>
</score-partwise>
"""


BAD_FIXTURES: dict[str, str] = {
    "malformed.xml": """<score-partwise version="4.0">
  <part-list>
    <score-part id="P1"><part-name>Piano</part-name></score-part>
  </part-list>
  <part id="P1">
    <measure number="1">
      <note><pitch><step>C</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type>
    </measure>
""",
    "zero_duration.musicxml": """<!-- bad input: a note with duration 0 (measure-sum check catches it). -->
<score-partwise version="4.0">
  <part-list>
    <score-part id="P1"><part-name>Piano</part-name></score-part>
  </part-list>
  <part id="P1">
    <measure number="1">
      <attributes>
        <divisions>12</divisions>
        <key><fifths>0</fifths></key>
        <time><beats>4</beats><beat-type>4</beat-type></time>
        <clef><sign>G</sign><line>2</line></clef>
      </attributes>
      <direction placement="above"><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>120</per-minute></metronome></direction-type></direction>
      <note><pitch><step>C</step><octave>4</octave></pitch><duration>0</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>D</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>E</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>F</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
  </part>
</score-partwise>
""",
    "measure_sum_mismatch.musicxml": """<!-- bad input: the second 4/4 measure has 3.5 beats of content. -->
<score-partwise version="4.0">
  <part-list>
    <score-part id="P1"><part-name>Piano</part-name></score-part>
  </part-list>
  <part id="P1">
    <measure number="1">
      <attributes>
        <divisions>12</divisions>
        <key><fifths>0</fifths></key>
        <time><beats>4</beats><beat-type>4</beat-type></time>
        <clef><sign>G</sign><line>2</line></clef>
      </attributes>
      <direction placement="above"><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>120</per-minute></metronome></direction-type></direction>
      <note><pitch><step>C</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>D</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>E</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>F</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
    <measure number="2">
      <note><pitch><step>G</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>A</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>B</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>C</step><octave>5</octave></pitch><duration>6</duration><voice>1</voice><type>eighth</type></note>
    </measure>
  </part>
</score-partwise>
""",
    "no_tempo.musicxml": """<!-- bad input: no tempo marks at all. -->
<score-partwise version="4.0">
  <part-list>
    <score-part id="P1"><part-name>Piano</part-name></score-part>
  </part-list>
  <part id="P1">
    <measure number="1">
      <attributes>
        <divisions>12</divisions>
        <key><fifths>0</fifths></key>
        <time><beats>4</beats><beat-type>4</beat-type></time>
        <clef><sign>G</sign><line>2</line></clef>
      </attributes>
      <note><pitch><step>C</step><octave>4</octave></pitch><duration>48</duration><voice>1</voice><type>whole</type></note>
    </measure>
  </part>
</score-partwise>
""",
    "out_of_range.musicxml": """<!-- bad input: a pitch above C8 (MIDI 108). -->
<score-partwise version="4.0">
  <part-list>
    <score-part id="P1"><part-name>Piano</part-name></score-part>
  </part-list>
  <part id="P1">
    <measure number="1">
      <attributes>
        <divisions>12</divisions>
        <key><fifths>0</fifths></key>
        <time><beats>4</beats><beat-type>4</beat-type></time>
        <clef><sign>G</sign><line>2</line></clef>
      </attributes>
      <direction placement="above"><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>120</per-minute></metronome></direction-type></direction>
      <note><pitch><step>D</step><octave>8</octave></pitch><duration>48</duration><voice>1</voice><type>whole</type></note>
    </measure>
  </part>
</score-partwise>
""",
    "ds_al_coda.musicxml": """<!-- bad input: D.S. al Coda (v0 rejects jump structures by name). -->
<score-partwise version="4.0">
  <part-list>
    <score-part id="P1"><part-name>Piano</part-name></score-part>
  </part-list>
  <part id="P1">
    <measure number="1">
      <attributes>
        <divisions>12</divisions>
        <key><fifths>0</fifths></key>
        <time><beats>4</beats><beat-type>4</beat-type></time>
        <clef><sign>G</sign><line>2</line></clef>
      </attributes>
      <direction placement="above"><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>120</per-minute></metronome></direction-type></direction>
      <note><pitch><step>C</step><octave>4</octave></pitch><duration>48</duration><voice>1</voice><type>whole</type></note>
      <direction placement="above"><direction-type><dalsegno/></direction-type></direction>
    </measure>
    <measure number="2">
      <note><pitch><step>D</step><octave>4</octave></pitch><duration>48</duration><voice>1</voice><type>whole</type></note>
      <direction placement="above"><direction-type><tocoda/></direction-type></direction>
    </measure>
    <measure number="3">
      <note><pitch><step>E</step><octave>4</octave></pitch><duration>48</duration><voice>1</voice><type>whole</type></note>
      <direction placement="above"><direction-type><coda/></direction-type></direction>
    </measure>
  </part>
</score-partwise>
""",
    "same_pitch_same_voice.musicxml": """<!-- bad input: two simultaneous same-pitch notes in one voice. -->
<score-partwise version="4.0">
  <part-list>
    <score-part id="P1"><part-name>Piano</part-name></score-part>
  </part-list>
  <part id="P1">
    <measure number="1">
      <attributes>
        <divisions>12</divisions>
        <key><fifths>0</fifths></key>
        <time><beats>4</beats><beat-type>4</beat-type></time>
        <clef><sign>G</sign><line>2</line></clef>
      </attributes>
      <direction placement="above"><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>120</per-minute></metronome></direction-type></direction>
      <note><pitch><step>C</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>C</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>E</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>F</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
  </part>
</score-partwise>
""",
    "voice_overflow.musicxml": """<!-- bad input: five voices in one staff (v0 normalizes 1-4, rejects more). -->
<score-partwise version="4.0">
  <part-list>
    <score-part id="P1"><part-name>Piano</part-name></score-part>
  </part-list>
  <part id="P1">
    <measure number="1">
      <attributes>
        <divisions>12</divisions>
        <key><fifths>0</fifths></key>
        <time><beats>4</beats><beat-type>4</beat-type></time>
        <clef><sign>G</sign><line>2</line></clef>
      </attributes>
      <direction placement="above"><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>120</per-minute></metronome></direction-type></direction>
      <note><pitch><step>C</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>D</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>E</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>F</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <backup><duration>48</duration></backup>
      <note><pitch><step>G</step><octave>4</octave></pitch><duration>12</duration><voice>2</voice><type>quarter</type></note>
      <backup><duration>48</duration></backup>
      <note><pitch><step>A</step><octave>4</octave></pitch><duration>12</duration><voice>3</voice><type>quarter</type></note>
      <backup><duration>48</duration></backup>
      <note><pitch><step>B</step><octave>4</octave></pitch><duration>12</duration><voice>4</voice><type>quarter</type></note>
      <backup><duration>48</duration></backup>
      <note><pitch><step>C</step><octave>5</octave></pitch><duration>12</duration><voice>5</voice><type>quarter</type></note>
    </measure>
  </part>
</score-partwise>
""",
    "chord_span.musicxml": """<!-- warning case: a chord spanning more than a 10th in one hand. -->
<score-partwise version="4.0">
  <part-list>
    <score-part id="P1"><part-name>Piano</part-name></score-part>
  </part-list>
  <part id="P1">
    <measure number="1">
      <attributes>
        <divisions>12</divisions>
        <key><fifths>0</fifths></key>
        <time><beats>4</beats><beat-type>4</beat-type></time>
        <clef><sign>G</sign><line>2</line></clef>
      </attributes>
      <direction placement="above"><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>120</per-minute></metronome></direction-type></direction>
      <note><pitch><step>C</step><octave>3</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><chord/><pitch><step>E</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>G</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>C</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>D</step><octave>5</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
  </part>
</score-partwise>
""",
    "glissando.musicxml": """<!-- bad input: glissando (no v0 normalization). -->
<score-partwise version="4.0">
  <part-list>
    <score-part id="P1"><part-name>Piano</part-name></score-part>
  </part-list>
  <part id="P1">
    <measure number="1">
      <attributes>
        <divisions>12</divisions>
        <key><fifths>0</fifths></key>
        <time><beats>4</beats><beat-type>4</beat-type></time>
        <clef><sign>G</sign><line>2</line></clef>
      </attributes>
      <direction placement="above"><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>120</per-minute></metronome></direction-type></direction>
      <note><pitch><step>C</step><octave>4</octave></pitch><duration>48</duration><voice>1</voice><type>whole</type><notations><glissando type="start"/></notations></note>
    </measure>
  </part>
</score-partwise>
""",
    "cue_note.musicxml": """<!-- bad input: cue note (no v0 normalization). -->
<score-partwise version="4.0">
  <part-list>
    <score-part id="P1"><part-name>Piano</part-name></score-part>
  </part-list>
  <part id="P1">
    <measure number="1">
      <attributes>
        <divisions>12</divisions>
        <key><fifths>0</fifths></key>
        <time><beats>4</beats><beat-type>4</beat-type></time>
        <clef><sign>G</sign><line>2</line></clef>
      </attributes>
      <direction placement="above"><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>120</per-minute></metronome></direction-type></direction>
      <note>
        <cue/>
        <pitch><step>C</step><octave>4</octave></pitch>
        <duration>12</duration>
        <voice>1</voice>
        <type>quarter</type>
      </note>
      <note><pitch><step>D</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>E</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
      <note><pitch><step>F</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>quarter</type></note>
    </measure>
  </part>
</score-partwise>
""",
    "turn_ornament.musicxml": """<!-- bad input: turn ornament (v0 expands trill/mordent only; turns are
     rejected by name). -->
<score-partwise version="4.0">
  <part-list>
    <score-part id="P1"><part-name>Piano</part-name></score-part>
  </part-list>
  <part id="P1">
    <measure number="1">
      <attributes>
        <divisions>12</divisions>
        <key><fifths>0</fifths></key>
        <time><beats>4</beats><beat-type>4</beat-type></time>
        <clef><sign>G</sign><line>2</line></clef>
      </attributes>
      <direction placement="above"><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>120</per-minute></metronome></direction-type></direction>
      <note><pitch><step>C</step><octave>4</octave></pitch><duration>48</duration><voice>1</voice><type>whole</type><notations><ornaments><turn/></ornaments></notations></note>
    </measure>
  </part>
</score-partwise>
""",
}


def main() -> None:
    FIXTURES.mkdir(parents=True, exist_ok=True)
    BAD.mkdir(parents=True, exist_ok=True)
    fixtures = {
        "pickup.musicxml": (FIXTURES / "pickup.musicxml").read_text(encoding="utf-8"),
        "key_change.musicxml": scale_fixture(),
        "triplets.musicxml": triplets_fixture(),
        "six_eight.musicxml": six_eight_fixture(),
        "ties_across.musicxml": ties_fixture(),
        "repeats_voltas.musicxml": repeats_fixture(),
        "grace_ornaments.musicxml": grace_ornaments_fixture(),
        "two_hands.musicxml": two_hands_fixture(),
        "multi_voice.musicxml": multi_voice_fixture(),
    }
    for name, content in fixtures.items():
        (FIXTURES / name).write_text(content)
        print(f"wrote fixtures/{name}")
    for name, content in BAD_FIXTURES.items():
        (BAD / name).write_text(content if name == "malformed.xml" else HEADER + content)
        print(f"wrote bad/{name}")


if __name__ == "__main__":
    main()
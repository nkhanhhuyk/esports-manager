package dev.uem.em.mgmt

/**
 * What people say about a competition change (comp.Formats): praise, criticism, overreactions, memes
 * and analysts' takes, by kind of change and its direction. [gain] / [lose]: the leagues that gained or
 * lost (short names, "the region" when none), [ev] the event, [game] the game's short name.
 */
object FormatTalk {
    class Lines(val praise: List<String>, val criticism: List<String>, val over: List<String>, val meme: List<String>, val analyst: List<String>)

    fun lines(kind: String, text: String, ev: String, gain: String, lose: String, game: String): Lines {
        val to = text.substringAfterLast("→ ", "").lowercase()
        return when (kind) {
            "seeds" -> Lines(
                listOf("$gain earned that seed. Two years of showing up at internationals, finally rewarded.", "Seeds based on results, not reputation. This is how it should work.", "About time $gain got the respect it deserves."),
                listOf("$lose losing a seed over a couple of bad events is a joke. Short-term thinking.", "So two seasons decide everything now? $lose will bounce back and then what, swap it again?", "Taking a seed from $lose punishes the fans, not the teams that flopped."),
                listOf("$lose is finished as a region. Mark it.", "$gain is the second-best region in the world now, don't @ me.", "Next step: $lose gets one seed and a participation trophy."),
                listOf("$lose fans opening the patch notes: 💀", "$gain fans after one good run: 'we're the best region'", "The seed was in $lose's pocket all along… and then it wasn't."),
                listOf("The numbers back it: $gain teams finished higher on average at $ev and the other internationals over the last two seasons.", "One seed moves. The fight for the last $lose place just became a bloodbath."))
            "expand" -> Lines(
                listOf("More teams at $ev means more storylines. Love it.", "A bigger $ev gives more regions a shot. Good for the scene.", "Expansion is great for the smaller teams, they need the exposure."),
                listOf("Watered down. Nobody wants to watch extra seeds get stomped for a week.", "Expanding $ev just means more filler before the real matches.", "More teams, same prize pool? Thought so."),
                listOf("$ev with this many teams is basically another league season.", "At this rate every team in $game qualifies for $ev by 2035."),
                listOf("$ev organisers: 'what if… more'", "Tickets to the extra-seeds showcase, get them while they last"),
                listOf("The extra places go to $gain: their next team in line gets a real chance.", "More teams means a longer first stage: deep rosters and good staff matter more."))
            "shrink" -> Lines(
                listOf("Smaller $ev, higher quality. Every series matters now.", "Fewer teams, no filler. Good call."),
                listOf("Cutting places hurts exactly the regions that need international experience to improve.", "$lose losing a place at $ev — how is the region supposed to grow?"),
                listOf("Might as well make $ev a two-region invitational at this point.", "The smaller regions will never qualify again."),
                listOf("$lose's last seed packing its bags: 🧳", "$ev getting smaller every patch like a phone battery"),
                listOf("Fewer places means the league playoffs carry even more weight this year.", "A tighter field: expect fewer blowouts in the first stage."))
            "playin+" -> Lines(
                listOf("Play-ins are where the best stories happen. Underdogs earning it.", "Finally the lowest seeds have to prove they belong.", "More teams travel, and the main event stays strong. Win-win."),
                listOf("Making seeds play extra matches before the main event is unfair to the smaller regions.", "Play-in teams arrive tired and half the main event is decided before it starts."),
                listOf("Play-in survivor to world champion arc incoming, I can feel it.", "The play-in will be more competitive than the main event, watch."),
                listOf("Play-in teams after a week of matches walking into the main event: 🧟", "The organisers inventing a new stage every patch:"),
                listOf("The top seeds skip straight to the main event: finishing high in the league matters more than ever.", "Play-in teams get extra games on the patch — sometimes that is an advantage, not a burden."))
            "playin-" -> Lines(
                listOf("No play-in, no extra week of filler. Everyone plays the real event.", "Every qualified team deserves the main stage. Good change."),
                listOf("The play-in was the most fun part of $ev and they killed it.", "Without a play-in the weaker regions lose their best shot at a deep run."),
                listOf("RIP play-ins. The best week of the year, gone.", "This is the worst format decision in $game history."),
                listOf("Play-in fans after the announcement: 😢", "Pour one out for the play-in 🫗"),
                listOf("With no play-in, the first stage decides everything: the seeding matters more.", "Fewer matches before the main event — teams arrive fresher."))
            "playin~" -> Lines(
                listOf("New play-in looks way more competitive.", "Finally a play-in that isn't just a formality."),
                listOf("Changing the play-in every few years just confuses everyone.", "Why fix what wasn't broken?"),
                listOf("This format is going to produce the biggest upset in $game history.", "Nobody from the play-in will win a single series in the main event, mark my words."),
                listOf("Explaining the new play-in to a casual fan: 📈📉🌀", "Me pretending to understand the new play-in: 🤓"),
                listOf("The new play-in changes how many lower seeds can reach the main event — check your region's path.", "Formats like this reward teams that peak early in the event."))
            "bracket" -> if (to.startsWith("double")) Lines(
                listOf("Double elimination means one bad series doesn't end a year of work.", "Lower-bracket runs are the best content in esports. Great change."),
                listOf("Double elim drags on forever and the lower final is always a rematch.", "More series, more travel, same champion. Boring."),
                listOf("The best team will never lose $ev again with this format.", "Lower bracket run to the title, calling it now."),
                listOf("Lower bracket run enjoyers: 😎", "Upper bracket teams waiting a week for the final: 💤"),
                listOf("Rematches are coming: teams that scout well will feast in the lower bracket.", "Double elimination rewards consistency over hot streaks."))
            else Lines(
                listOf("Single elimination is pure hype. Win or go home.", "Every bracket series a final. This is what esports should be."),
                listOf("One bad day and a region's best team is out. Brutal, and not in a good way.", "Single elim turns a year of work into a coin flip."),
                listOf("Upsets every round, the favourite is going home early, guaranteed.", "This format will crown the luckiest team, not the best."),
                listOf("Single elimination: the format where the draft is the real MVP", "Favourites seeing the new bracket: 😬"),
                listOf("Every series is an elimination: expect safer drafts and comfort picks.", "Single elimination rewards teams that peak at the right moment."))
            "group" -> when {
                to.contains("swiss") -> Lines(
                    listOf("Swiss rewards consistency and kills the dead matches.", "Teams play opponents with their own record. Fair and exciting."),
                    listOf("Swiss is impossible to follow for casual viewers.", "Swiss pairings make the schedule unreadable. Bring the groups back."),
                    listOf("Swiss is the best format ever invented, change my mind.", "The 2-2 decider day will be the best day of the year."),
                    listOf("Trying to read a Swiss table: 🤯", "Me refreshing the Swiss draw every five minutes"),
                    listOf("Swiss gives strong teams an easier path early: upsets matter more in the decider rounds.", "Three wins to advance: depth of champion pool will decide the 2-2 matches."))
                to.contains("group") -> Lines(
                    listOf("Groups bring back the group of death. Can't wait for the draw.", "Everyone plays everyone in their group. Simple and fair."),
                    listOf("Groups mean dead rubbers on the last day.", "One bad draw and a top team is out. Groups are a lottery."),
                    listOf("The group of death this year will have three champions in it.", "Some region will get a free group and walk into the knockout."),
                    listOf("The group draw is the real final", "Group of death survivors: 🫡"),
                    listOf("With fixed groups, tiebreakers will decide places: game difference matters.", "Groups give teams more time to adapt to the patch."))
                else -> Lines(
                    listOf("Straight to the bracket, every match matters.", "No group stage, no filler. Good."),
                    listOf("No first stage means fewer games for the fans.", "Teams travel across the world for one series? Harsh."),
                    listOf("The whole event will be over before the patch is even figured out.", "Upset city from day one."),
                    listOf("Bracket on day one: ⚡", "The group stage got patched out"),
                    listOf("Without a first stage the seeding decides the bracket path.", "Fewer series means less time to adapt: prepared drafts win."))
            }
            "bo" -> if (to.startsWith("bo1")) Lines(
                listOf("Bo1s are chaos and chaos is entertaining.", "More matches in a day, more upsets. Fun."),
                listOf("Bo1s are coin flips. Months of work decided by one draft.", "One cheese pick and a top team is out. Terrible."),
                listOf("Every favourite is going 1-3 in Bo1s, watch.", "Bo1s will produce the craziest results we've ever seen."),
                listOf("Bo1 enjoyers when their team loses to a cheese pick: 🫠", "Bo1: one draft, one prayer 🙏"),
                listOf("Bo1s favour teams with prepared pocket picks.", "Short series reward early-game teams: expect fast, aggressive drafts."))
            else Lines(
                listOf("Longer series, the better team wins. Simple.", "Best-of-five knockouts: finally, proper series."),
                listOf("Longer series mean a longer event and more tired players.", "Fewer upsets, fewer stories. Longer isn't always better."),
                listOf("Bo5 everywhere or I'm not watching.", "The favourite will win every Bo5 now, it's over."),
                listOf("Drafters preparing five games of strats: 😵‍💫", "Game 5 enjoyers: ❤️"),
                listOf("Longer series reward deep champion pools; one-trick teams are in trouble.", "Expect more adaptation between games: the coach's draft matters more."))
            "qual" -> if (text.contains("circuit points")) Lines(
                listOf("Rewarding a whole year of results is fairer than one playoff run.", "Consistency finally pays."),
                listOf("A points table kills the drama: the playoffs barely matter for Worlds now.", "No more Cinderella runs. Boring."),
                listOf("The points table will send the same teams every year forever.", "Spring results deciding Worlds in autumn? Peak."),
                listOf("Spreadsheet enjoyers rejoice 📊", "Me calculating points scenarios in July: 🧮"),
                listOf("Every split counts now: a bad first split can cost a Worlds place.", "Teams will rotate less and play their best roster all year."))
            else Lines(
                listOf("Playoffs matter again. Upsets get rewarded.", "A playoff run to Worlds is the best story in esports."),
                listOf("A whole season of dominance means nothing after one bad week. Brutal.", "One upset and the best team of the year stays home."),
                listOf("Some fourth seed is going to go on a run and win Worlds, calling it.", "Regular season is pointless now."),
                listOf("Cinderella runs are back on the menu 🎃", "Regular season champions watching the playoffs: 😰"),
                listOf("Peaking in the last split is now worth more than a whole season of points.", "Expect teams to save strategies for the playoffs."))
            "winner", "runner" -> if (text.contains("no longer") || text.contains("No more")) Lines(
                listOf("Every Worlds place earned at home, as it should be.", "Fairer: domestic seasons decide who goes."),
                listOf("Taking away the reward makes the mid-season event feel meaningless.", "Why try hard at the mid-season event now?"),
                listOf("Nobody will care about the mid-season event anymore.", "Teams will send their subs to the mid-season event, watch."),
                listOf("Mid-season champions after the announcement: 🥲", "That ticket had sentimental value"),
                listOf("The leagues decide every place again: the last split's playoffs carry more weight.", "One place moves back to the leagues."))
            else Lines(
                listOf("Winning the mid-season event should mean something. Perfect.", "Big runs get rewarded. Love it."),
                listOf("So one good tournament in June skips a whole playoff season?", "Extra seeds for one region is unfair to everyone else."),
                listOf("The mid-season event is now bigger than Worlds.", "The same region will get the extra seed every year."),
                listOf("Teams at the mid-season event this year: 🏃💨", "Free Worlds ticket speedrun"),
                listOf("A mid-season title now books a Worlds place: expect full-strength rosters there.", "The extra place reshapes the domestic race in the region that earns it."))
            "lcq" -> if (text.contains("return")) Lines(
                listOf("Regional finals are peak drama. Win or go home for a Worlds ticket.", "A last chance for the teams that fell short. Great."),
                listOf("Another qualifier. These players never get a break.", "The playoffs should decide it, not a mini tournament."),
                listOf("A team will go from sixth to world champion through the regional finals, watch.", "Best week of the domestic season, no contest."),
                listOf("Regional finals teams the week before Worlds: 🔥🔥🔥", "Sixth seed with a dream"),
                listOf("The next six on points get a second chance: every point in the regular season matters.", "Regional finals winners arrive in form — dangerous at Worlds."))
            else Lines(
                listOf("Fewer matches at the end of the year. Players need the rest.", "Cleaner: the points table decides."),
                listOf("They scrapped the regional finals? That was the best week of the year.", "No last chance means fewer stories."),
                listOf("The domestic season is ruined.", "Worst decision of the decade."),
                listOf("Regional finals: gone, reduced to atoms", "Pour one out for the last-chance qualifier 🫗"),
                listOf("Without regional finals, the points table decides the last places: every series counts.", "Teams on the edge will play for points all year."))
            "dcgi" -> if (text.contains("cancelled")) Lines(
                listOf("Nobody watched it anyway.", "The calendar needed the space."),
                listOf("That invitational was where rookies got their first international games.", "Teams that miss Worlds get nothing now. Sad."),
                listOf("The end of the best-of-the-rest era. RIP.", "Off-season is going to be so boring."),
                listOf("Teams that miss Worlds: 🛋️", "Best of the rest, rest in peace"),
                listOf("Teams outside Worlds lose an autumn of official games: expect more scrim tours.", "Fewer international games for the second tier of each league."))
            else Lines(
                listOf("Teams that miss Worlds deserve a stage too. Great content.", "Best of the rest gives rookies international experience."),
                listOf("Who asked for a tournament of teams that weren't good enough?", "Nobody will watch this with Worlds on at the same time."),
                listOf("The invitational will be more fun than Worlds.", "Some team will win this and then win Worlds next year, calling it."),
                listOf("Teams that missed Worlds packing their bags anyway: ✈️", "Consolation cup enjoyers 🏆"),
                listOf("A real stage for the fifth to eighth teams: good for development and for scouting.", "International games for the teams that just missed out."))
            "prorel-" -> Lines(
                listOf("Stability means orgs can finally invest long term.", "Franchising brings sponsors. Good for player salaries."),
                listOf("Franchising kills the dream for every tier-2 team.", "Closed leagues get stale. No relegation battles, no drama."),
                listOf("The lower leagues are dead now. Thanks for nothing.", "Bottom teams will tank every season now."),
                listOf("Bottom team of the league after the announcement: 😌", "Relegation zone: deleted"),
                listOf("Expect bigger contracts and fewer roster gambles: nobody can go down.", "The lower leagues lose their way up: their best players will move up by transfer instead."))
            "prorel+" -> Lines(
                listOf("Relegation battles are back! Every match matters now.", "$gain finally has something to play for."),
                listOf("Orgs won't invest with relegation hanging over them.", "Pro/rel sounds great until your team goes bankrupt in the second division."),
                listOf("A big name will be relegated within two seasons, watch.", "The lower leagues will produce a champion within five years."),
                listOf("Bottom of the table teams opening the news: 😰", "Relegation zone enjoyers: 🍿"),
                listOf("Relegation risk changes everything: expect panic signings before the last split.", "The bottom two now play for their place against the best of the lower leagues."))
            else -> Lines(listOf("Good change."), listOf("Bad change."), listOf("This changes everything."), listOf("Patch notes enjoyers 📜"), listOf("We'll see how it plays out."))
        }
    }
}

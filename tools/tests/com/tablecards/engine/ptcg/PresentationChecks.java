package com.tablecards.engine.ptcg;

import com.tablecards.engine.*;
import java.util.*;

public final class PresentationChecks {
    static void require(boolean b,String why){if(!b)throw new AssertionError(why);}
    public static void main(String[] args){
        List<PtcgCard> deck=new ArrayList<>();
        for(int i=0;i<40;i++)deck.add(new PtcgCard("p"+i%10,Map.of("name","Fixture "+i%10,"kind","pokemon","stage",0,"hp",100,"attacks",List.of(Map.of("name","Hit","damage",40,"cost",List.of("colorless"))))));
        for(int i=0;i<20;i++)deck.add(new PtcgCard("energy",Map.of("name","Fire Energy","kind","energy","type","fire")));
        boolean benched=false,attached=false;
        for(int run=0;run<3;run++){
            PtcgGame game=new PtcgGame(new String[]{"A","B"},deck,deck,run);
            require(game.drainPresentation().isEmpty(),"Opening hands do not reveal");
            for(int step=0;step<1500 && !game.isOver();step++){
                Decision d=game.pending();int index=game.bot().choose(game,d);
                // Finish setup early to exercise normal bench plays explicitly.
                for(int i=0;i<d.options().size();i++)if(d.options().get(i).move().type().equals("setup_done")){index=i;break;}
                String type=d.options().get(index).move().type();
                game.choose(d.player(),index);var events=game.drainPresentation();
                if(type.startsWith("setup_"))require(events.isEmpty(),"Face-down setup remains private");
                if(type.equals("bench")){benched=true;require(events.stream().anyMatch(e->e.get("kind").equals("reveal")),"Bench play reveals");}
                if(type.equals("energy_target")){attached=true;require(events.stream().anyMatch(e->e.get("kind").equals("reveal")),"Energy attachment reveals");}
                ViewJson.write(game,-1,null,new LinkedHashMap<>());require(game.drainPresentation().isEmpty(),"Snapshots are silent");
            }
        }
        require(benched && attached,"Fixtures cover public Pokemon and Energy plays");
        System.out.println("PASS: Pokemon bench/Energy/setup/snapshot checks");
    }
}

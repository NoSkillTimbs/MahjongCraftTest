package com.tablecards.client;

import com.tablecards.engine.*;
import com.tablecards.engine.ygo.*;
import com.tablecards.engine.ptcg.*;
import java.util.*;
import java.nio.file.*;

/** Dependency-free regression checks. Fixtures exercise behavior, not completeness of real card scripts. */
public final class ChangeChecks {
    private static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        Map<String,Object> definitions=new LinkedHashMap<>();
        for(int i=1;i<=14;i++)definitions.put("m"+i,Map.of("name","Fixture monster "+i,"kind","monster","level",4,"atk",1000,"def",1000,"codes",List.of(""+i)));
        definitions.put("fusion",Map.of("name","Fixture fusion","kind","monster","frame","fusion","codes",List.of("99")));
        YgoLibrary ygo=YgoLibrary.parse(JsonWriter.write(Map.of("cards",definitions)));
        String main="";for(int i=0;i<40;i++)main+=(i%14+1)+"\n";
        String text="#main\n"+main+"#extra\n99\n!side\n1\n";
        var parsed=DeckLists.ydk(text,ygo);
        check(parsed.ok(),"Valid fixture YDK must load");check(parsed.deck().size()==41,"Extra is playable, side is excluded");
        check(DeckLists.readYdk(text,ygo).side().size()==1,"Side preserved for editing");
        check(!DeckLists.ydk("#main\nnot-a-code\n999999999999999999999",ygo).ok(),"Malformed IDs reported");
        check(DeckLists.ydk(text+"unsupported-side-card\n",ygo).ok(),"Legacy side contents cannot invalidate playable main/extra");
        DeckBuilderModel model=new DeckBuilderModel();
        for(var c:parsed.deck())model.add(new DeckBuilderModel.Entry(c.id,c.codes.get(0),"main",c.isExtra(),new ViewModel.Card(ViewJson.snapshot(YgoGame.definitionView(c)))),false);
        var card=model.deck.get(0);model.add(card,true);
        check(DeckLists.ydk(model.serialize(),ygo).ok(),"Editor-generated YDK accepted by existing loader");
        check(DeckLists.readYdk(model.serialize(),ygo).side().size()==1,"Editor side round trip");
        Path tmp=Files.createTempDirectory("mahjong-deck-check");
        DeckFiles.save(tmp,"Round trip","ygo",model.serialize());
        check(DeckFiles.load(tmp,"Round trip","ygo").equals(model.serialize()),"Atomic local save/load");
        check(DeckFiles.names(tmp,"ygo").equals(List.of("Round trip")),"Local list");
        try{DeckFiles.save(tmp,"../escape","ygo",text);throw new AssertionError("Path traversal accepted");}catch(java.io.IOException expected){checks++;}
        Path link=tmp.resolve("tablecards/decks/Link.ydk");Files.createSymbolicLink(link,tmp.resolve("elsewhere"));
        try{DeckFiles.save(tmp,"Link","ygo",text);throw new AssertionError("Symlink accepted");}catch(java.io.IOException expected){checks++;}

        var pk=new LinkedHashMap<String,Object>();
        for(int i=1;i<=10;i++)pk.put("p"+i,Map.of("name","Fixture Pokemon "+i,"kind","pokemon","stage",0,"hp",60,"set","TST","number",""+i));
        pk.put("energy",Map.of("name","Fire Energy","kind","energy","type","fire","set","TST","number","11"));
        PtcgLibrary ptcg=PtcgLibrary.parse(JsonWriter.write(Map.of("cards",pk)));
        model.game="ptcg";model.deck.clear();
        for(var c:ptcg.cards.values())for(int n=0;n<(c.kind==PtcgCard.Kind.ENERGY?20:4);n++)model.add(new DeckBuilderModel.Entry(c.id,c.name+" "+c.set+" "+c.number,"main",false,new ViewModel.Card(ViewJson.snapshot(PtcgGame.cardView(c)))),false);
        check(DeckLists.ptcg(model.serialize(),ptcg).ok(),"Pokemon editor export accepted by existing parser");
        check(!DeckLists.ptcg("999999999999999 Fire Energy",ptcg).ok(),"Oversized count safely rejected");
        check(!DeckLists.ptcg("1000 Fire Energy\n1000 Fire Energy",ptcg).ok(),"Resource bound rejects huge deck");
        DeckFiles.save(tmp,"Pokemon","ptcg",model.serialize());
        check(DeckLists.ptcg(DeckFiles.load(tmp,"Pokemon","ptcg"),ptcg).ok(),"Pokemon local round trip");

        // Compare all five central slot columns for both seats, four table directions and either viewer.
        List<YgoCard> deck=parsed.deck();YgoGame game=new YgoGame(new String[]{"A","B"},deck,deck,12);
        for(String dir:List.of("north","south","east","west"))for(int seat=0;seat<2;seat++){
            Map<String,Object> view=new LinkedHashMap<>();view.put("seat",seat);view.put("dir0",dir);view.put("table",List.of(0,0,0));
            ViewJson.write(game,seat,game.pending(),view);ViewModel v=ViewModel.parse(JsonWriter.write(view));
            var items=TableLayout.build(v,Map.of(),0,new TableLayout.Highlight());
            // The five monster slots appear first for each side; spell slots next.
            for(int row=0;row<2;row++){
                List<Double> a=new ArrayList<>(),b=new ArrayList<>();
                for(int sideIndex=0;sideIndex<2;sideIndex++){
                    final int si=sideIndex;
                    var slots=items.stream().filter(it->it.kind==TableLayout.Kind.SLOT && it.side==si).toList();
                    for(int n=row*5;n<row*5+5;n++){
                        var it=slots.get(n);double lateral=(dir.equals("north")||dir.equals("south"))?it.c[0]:it.c[2];
                        (si==0?a:b).add(lateral);
                    }
                }
                Collections.sort(a);Collections.sort(b);
                for(int i=0;i<5;i++)check(Math.abs(a.get(i)-b.get(i))<1e-9,"Opposing central slots align");
            }
        }
        var map=new LinkedHashMap<String,Object>();map.put("table",List.of(0,0,0));map.put("seat",0);ViewJson.write(game,0,game.pending(),map);
        ViewModel v=ViewModel.parse(JsonWriter.write(map));
        TableViews.clear();TableViews.accept(v,"test",0);
        check(Presentation.lines(v.table,0).isEmpty(),"Snapshot alone has no events");
        var reveal=Map.of("kind","reveal","source",123,"card",ViewJson.snapshot(new Board.CardView("Public play")));
        Presentation.accept(JsonWriter.write(Map.of("table",List.of(0,0,0),"game","ygo","events",List.of(reveal,Map.of("kind","attack","source",123,"target",456)))),100);
        check(Presentation.lines(v.table,101).size()==1,"Live line received");
        check(Presentation.lines(v.table,1600).isEmpty(),"Line expires");
        TableViews.clear();TableViews.accept(v,"test",1700);
        check(Presentation.lines(v.table,1700).isEmpty(),"Reconnect snapshot cannot replay effects");
        System.out.println("PASS: "+checks+" persistence, geometry and presentation checks");
    }
}

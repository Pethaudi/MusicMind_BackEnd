package com.musicmindproject.backend.rest.endpoints;

import com.google.gson.GsonBuilder;
import com.musicmindproject.backend.entities.Play;
import com.musicmindproject.backend.entities.User;
import com.musicmindproject.backend.entities.enums.MusicGenre;
import com.musicmindproject.backend.logic.PersonalityEvaluator;
import com.musicmindproject.backend.logic.PersonalityImageGenerator;
import com.musicmindproject.backend.logic.database.PlaysManager;
import com.musicmindproject.backend.logic.database.QuestionManager;
import com.musicmindproject.backend.logic.database.UserManager;

import javax.inject.Inject;
import javax.json.JsonObject;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.Random;
import java.util.stream.Collectors;

@Path("music")
public class MusicEndpoint {
    @Inject
    private UserManager userManager;
    @Inject
    private PersonalityEvaluator evaluator;
    @Inject
    private QuestionManager questionManager;
    @Inject
    private PlaysManager playsManager;
    @Inject
    private PersonalityImageGenerator personalityImageGenerator;

    private final String PATHNAME = "/mnt/sequences_tmp/melody_rnn/";

    /**
     * @param id ID of the user
     * @return JsonObject with Personality, Username, UserId, Path to Music-Track
     */
    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response doMusicGet(@PathParam("id") String id) {
        return Response.ok().entity(new GsonBuilder().create().toJson(userManager.retrieve(id))).build();
    }

    /**
     * @param answer Answers the user has given plus the Username and the ID
     * @return JSON-Object with the personality of the user represented by the Big-Five (see Wikipedia for more)
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response doMusicPost(JsonObject answer) {
        int totalNumberOfQuestions = questionManager.getNumberOfAvailableQuestions();

        if (answer.size() != totalNumberOfQuestions + 1) {
            System.err.println("Bad input (number of elements != total number of questions (" + totalNumberOfQuestions + ")");
            return Response.notModified("Bad input (number of elements != total number of questions (" + totalNumberOfQuestions + ")").build();
        }

        double[] answerNumbers = new double[answer.size() - 2];
        String userID = answer.getString("" + (answer.size() - 1));
        String userName = answer.getString("" + (answer.size() - 2));

        for (int i = 0; i < answerNumbers.length; i++) {
            answerNumbers[i] = Integer.parseInt(answer.getString("" + i));
        }

        double[] values = evaluator.getOutputs(answerNumbers);
        File musicFile = createMusicFile(userName, userID, values);
        User user = storeUser(userID, userName, musicFile.getName(), values);

        return Response.ok().entity(new GsonBuilder()
                .create()
                .toJson(user))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
    private File convertToWAV(File toConvert) {
        AudioFileFormat.Type outputType = Arrays.stream(AudioSystem.getAudioFileTypes()).filter(ft -> ft.getExtension().equals("wav")).collect(Collectors.toList()).get(0);

        if(outputType == null){
            System.out.println("Output type not supported.");
            return toConvert;
        }

        try{
            AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(toConvert);

            AudioSystem.write(audioInputStream,
                    outputType,
                    new File(toConvert.getAbsolutePath().substring(0, toConvert.getAbsolutePath().length() - 4)));
        }catch (Exception e){
            e.printStackTrace();
        }


        return toConvert;
    }
    private File createMusicFile(String userName, String userID, double[] values) {
        File musicTrack = convertToWAV(findFileForUser(values));
        File destination = new File(PATHNAME + "used_tracks/" + userID.hashCode() + "_" + userName + "s_music.wav");

        try {
            Files.move(musicTrack.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return destination;
    }
    private User storeUser(String userID, String userName, String fileName, double[] values) {
        User user = userManager.retrieve(userID);
        if (user == null)
            user = new User(userID, userName, fileName, values[4], values[1], values[3], values[2], values[0]);
        else {
            user.setAgreeableness(values[2]);
            user.setConscientiousness(values[1]);
            user.setExtraversion(values[3]);
            user.setNeuroticism(values[0]);
            user.setOpenness(values[4]);
            user.setUserName(userName);
            user.setPlays(0);
            user.setShares(0);
            user.setPathToMusicTrack(fileName);
        }
        userManager.store(user);
        personalityImageGenerator.generatePersonalityImage(user);
        return user;
    }
    private File findFileForUser(double[] values) {
        //TODO FIND SPECIFIC FILE FOR USER

        Random rand = new Random();
        MusicGenre g = MusicGenre.values()[Math.abs(rand.nextInt() % MusicGenre.values().length)];
        File[] filesAvailable = new File(PATHNAME + "generated_tracks/" + g.name().toLowerCase() + "_" + g.getInstruments().get(Math.abs(rand.nextInt() % g.getInstruments().size())).name().toLowerCase()).listFiles();
        return filesAvailable[Math.abs(rand.nextInt() % Objects.requireNonNull(filesAvailable).length)];
    }

    /**
     * @param query Fixed Keywords:
     *              - newest
     *              - hottest
     *              - everything else: name of the user or music-track
     * @param min   First Track to return
     * @param max   Last track to return
     * @return JsonArray of doMusicGet() with all Users (between min and max)
     */
    @GET
    @Path("{query}/{min}/{max}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response doMusicGetForExplore(@PathParam("query") String query, @PathParam("min") int min, @PathParam("max") int max) {
        return Response.ok(userManager.retrieveMany(min, max, query)).build();
    }

    /**
     * @param music object:
     *              - player = id of person who played music
     *              - played = id of person who created music
     */
    @POST
    @Path("play")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response increasePlays(JsonObject music) {
        Play play = new GsonBuilder().create().fromJson(music.toString(), Play.class);

        if (playsManager.retrieve(play) == null) {
            playsManager.store(play);
            User u = userManager.retrieve(play.getPlayed());
            u.setPlays(u.getPlays() + 1);
            userManager.store(u);
        }
        return Response.noContent().build();
    }
}

package fr.keycloak.kywan;

import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClientBuilder;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.guild.MemberChunkEvent;
import discord4j.core.event.domain.guild.MemberUpdateEvent;
import discord4j.core.event.domain.lifecycle.ReadyEvent;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.event.domain.role.RoleCreateEvent;
import discord4j.core.event.domain.role.RoleDeleteEvent;
import discord4j.core.event.domain.role.RoleUpdateEvent;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.Role;
import org.apache.log4j.Logger;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.RoleRepresentation;
import reactor.core.publisher.Flux;

import javax.ws.rs.NotFoundException;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class Main {
    private static final Logger log = Logger.getLogger(Main.class);

    private static final String GUILDID = System.getenv("GUILD_ID");
    private static final String DISCORDTOKEN = System.getenv("DISCORD_TOKEN");
    private static final String REALM = System.getenv("REALM");
    private static final String SERVERURL = System.getenv("SERVER_URL");
    // idm-client needs to allow "Direct Access Grants: Resource Owner Password Credentials Grant"
    private static final String CLIENTID = System.getenv("CLIENT_ID");
    private static final String CLIENTSECRET = System.getenv("CLIENT_SECRET");
    // User "idm-admin" needs at least "manage-users, view-clients, view-realm, view-users" roles for "realm-management"
    private static final String KEYCLOAKUSERNAME = System.getenv("KEYCLOAK_USERNAME");
    private static final String KEYCLOAKUSERPASSWORD = System.getenv("KEYCLOAK_PASSWORD");

    public static void main(String[] args) {

        GatewayDiscordClient client;
                // Creation of discord bot
        client = DiscordClientBuilder.create(DISCORDTOKEN)
                .build()
                .login()
                .block();

        // Creation of keycloak connection
        Keycloak keycloak =  KeycloakBuilder.builder()
                .serverUrl(SERVERURL)
                .realm(REALM)
                .grantType(OAuth2Constants.PASSWORD)
                .clientId(CLIENTID)
                .clientSecret(CLIENTSECRET)
                .username(KEYCLOAKUSERNAME)
                .password(KEYCLOAKUSERPASSWORD)
                .build();

        // Get realm
        RealmResource realmResource = keycloak.realm(REALM);

        assert client != null;
        client.getEventDispatcher().on(RoleUpdateEvent.class)
                .subscribe(roleUpdate -> {
                    log.debug("RoleUpdateEvent Enter");
                    log.debug(roleUpdate);
                    
                    String roleName = roleUpdate.getCurrent().getName();
                    if (roleUpdate.getOld().isPresent()) {
                        RoleRepresentation keycloakRole = realmResource.roles().get(roleUpdate.getOld().get().getName()).toRepresentation();
                        keycloakRole.setName(roleName);
                        realmResource.roles().get(roleUpdate.getOld().get().getName()).update(keycloakRole);
                    }
                    else {
                        log.error("No old role, impossible to modify in keycloak !");
                        log.error("Creation of the new role !");
                        createNewRole(realmResource, roleName);
                        addAllUsersInRole(realmResource, realmResource.roles().get(roleName).toRepresentation(), client.getGuildMembers(Snowflake.of(GUILDID)));
                    }

                    log.debug("RoleUpdateEvent Exit");
                });

        client.getEventDispatcher().on(RoleCreateEvent.class)
                .subscribe(role -> {
                    log.debug("RoleCreateEvent Enter");
                    log.debug(role);

                    String roleName = role.getRole().getName();
                    createNewRole(realmResource, roleName);

                    log.debug("RoleCreateEvent Exit");
                });

        client.getEventDispatcher().on(RoleDeleteEvent.class)
                .subscribe(event -> {
                    log.debug("RoleDeleteEvent Enter");
                    log.debug(event);
                    if (event.getRole().isPresent()){
                        realmResource.roles().deleteRole(event.getRole().get().getName());
                        log.debug(String.format("Role %s is delete", event.getRole().get().getName()));
                    }
                    else {
                        log.error("Impossible to delete the role. Role not found");
                    }
                    log.debug("RoleDeleteEvent Exit");
                });

        client.getEventDispatcher().on(MemberUpdateEvent.class)
                .subscribe(event -> {
                    log.debug("MemberUpdateEvent Enter");
                    log.debug(event);

                    Member member = Objects.requireNonNull(event.getMember().block());
                    List<Role> roles = Objects.requireNonNull(member.getRoles().collectList().block());
                    Map<String, String> kcUserDiscord = getKcUserDiscord(realmResource);
                    String userDiscordId = kcUserDiscord.get(member.getId().asString());
                    UserResource userRessource = getUserResource(realmResource, userDiscordId);
                    if (event.getOld().isPresent()) {
                        List<Role> rolesOld = Objects.requireNonNull(event.getOld().get().getRoles().collectList().block());
                        // REMOVE
                        if (roles.size() < rolesOld.size()){
                            roles.removeAll(rolesOld);
                            List<RoleRepresentation> roleRepresentationList = new ArrayList<>();
                            roles.forEach(role -> roleRepresentationList.add(realmResource.roles().get(role.getName()).toRepresentation()));
                            userRessource.roles().realmLevel().remove(roleRepresentationList);
                        }
                        // ADD
                        else if (roles.size() > rolesOld.size()){
                            rolesOld.removeAll(roles);
                            List<RoleRepresentation> roleRepresentationList = new ArrayList<>();
                            rolesOld.forEach(role -> roleRepresentationList.add(realmResource.roles().get(role.getName()).toRepresentation()));
                            userRessource.roles().realmLevel().add(roleRepresentationList);
                        }
                        else {
                            // No modification on roles
                        }
                    }
                    else {
                        // impossible to have old, so we add all roles of user
                        List<RoleRepresentation> roleRepresentationList = new ArrayList<>();
                        roles.forEach(role -> roleRepresentationList.add(realmResource.roles().get(role.getName()).toRepresentation()));
                        userRessource.roles().realmLevel().add(roleRepresentationList);
                    }
                    log.debug("MemberUpdateEvent Exit");
                });

        client.getEventDispatcher().on(MemberChunkEvent.class)
                .subscribe(event -> {
                    log.debug("MemberChunkEvent Enter");
                    log.debug(event);
                    log.debug(String.format("%s Member(s) Collect", (long) event.getMembers().size()));
                    log.debug("MemberChunkEvent Exit");
                });

        client.getEventDispatcher().on(ReadyEvent.class)
                .subscribe(user -> {
                    log.debug("ReadyEvent Enter");
                    log.debug(user);
                    log.info(String.format("Logged in as %s#%s", user.getSelf().getUsername(), user.getSelf().getDiscriminator()));

                    // Trigger the MemberChunkEvent to store all users
                    client.requestMembers(Snowflake.of(GUILDID)).blockFirst();

                    Supplier<Stream<String>> discordRolesName = () -> Objects.requireNonNull(client.getGuildRoles(Snowflake.of(GUILDID)).collectList().block()).stream().map(Role::getName);
                    discordRolesName.get().forEach(roleName -> {
                        try {
                            RoleRepresentation testerRealmRole = realmResource.roles().get(roleName).toRepresentation();
                            log.debug(String.format("Role : %s found in realm", testerRealmRole.getName()));
                        } catch (NotFoundException e) {
                            createNewRole(realmResource, roleName);
                        }

                    });

                    log.debug("ReadyEvent Exit");
                });

        client.getEventDispatcher().on(MessageCreateEvent.class)
                .subscribe(message -> {
                    log.debug("MessageEvent Enter");
                    //if(message.getMessage().getContent().equals("0d12a08d-d6d8-47c0-8b7e-b6b3bf841371")) {
                    //    List<RoleRepresentation> roleRepresentationList = realmResource.roles().list();
                    //    roleRepresentationList.forEach(roles -> {
                    //        // I use description because atribute dont work great
                    //        if (roles.getDescription().contains("discord role created automatically")) {
                    //            realmResource.rolesById().deleteRole(roles.getId());
                    //            log.info(String.format("%s role delete in realm", roles.getName()));
                    //        }
                    //    });
                    //}
                    log.info(message.getMessage().getContent());
                    log.debug("MessageEvent Exit");
                });

        client.onDisconnect().block();
    }

    private static void createNewRole(RealmResource realmResource, String roleName) {
        RoleRepresentation newRole = new RoleRepresentation(roleName, "discord role created automatically " + new Date(), false);
        realmResource.roles().create(newRole);
        log.info(String.format("%s role added in realm", roleName));
    }

    public static Predicate<Role> isRoleMatch(String roleName)
    {
        return p -> p.getName().equals(roleName);
    }

    private static void addAllUsersInRole(RealmResource realmResource, RoleRepresentation role, Flux<Member> guildMembers){
        Map<String, String> kcUserDiscord = getKcUserDiscord(realmResource);
        guildMembers.filter(member -> Objects.requireNonNull(member.getRoles().collectList().block()).stream().anyMatch(isRoleMatch(role.getName()))).collectList().block().forEach(member -> {
           String userDiscordId = kcUserDiscord.get(member.getId().asString());
            UserResource userRessource = getUserResource(realmResource, userDiscordId);
            if(userRessource != null){
                userRessource.roles().realmLevel().add(Collections.singletonList(role));
            }
        });
    }

    private static UserResource getUserResource(RealmResource realmResource, String userKeycloakId) {
        UsersResource usersRessource = realmResource.users();
        UserResource userRessource = null;
        if (userKeycloakId != null) {
            userRessource = usersRessource.get(userKeycloakId);
        }
        return userRessource;
    }

    // Key = id discord | value = id kc
    private static Map<String, String> getKcUserDiscord(RealmResource realmResource) {
        Map<String, String> kcUserDiscord = new HashMap<>();
        realmResource.users().list().forEach(userRepresentation -> {
            UserResource ur = realmResource.users().get(userRepresentation.getId());
            if (ur.getFederatedIdentity() != null && !ur.getFederatedIdentity().isEmpty()){
                ur.getFederatedIdentity().forEach(federatedIdentityRepresentation -> {
                    if (federatedIdentityRepresentation.getIdentityProvider().equals("discord")){
                        kcUserDiscord.put(federatedIdentityRepresentation.getUserId(), userRepresentation.getId());
                    }
                });
            }
        });
        return kcUserDiscord;
    }

    // TODO think to remove active session when we update an user
}

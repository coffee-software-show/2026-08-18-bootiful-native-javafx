create table if not exists users
(
    username text    not null primary key,
    password text    not null,
    enabled  boolean not null
);

create table if not exists authorities
(
    username  text not null references users (username),
    authority text not null
);

create unique index if not exists index_auth_username ON authorities USING btree (username, authority);


insert into users (username, password, enabled) values ('catherine', '{bcrypt}$2a$10$H20/wTYHzZDRkEMaXycDUOvITjbzdJ/ngwVPNQWsYA3QgbQl.j/6G', true);
insert into users (username, password, enabled) values ('josh', '{sha256}ed1a0e0cc973b58b6d5e2c04a646f213193943fc505aa3ffe50bde28f7cf20b4b67b1fbf6c911552', true);
insert into users (username, password, enabled) values ('mala', '{bcrypt}$2a$10$JSLA2uTlXq6kInaXVyuTV.bmZvz8zRLhGrSzoLOCmDTNDu5ZyA6jG', true);
insert into users (username, password, enabled) values ('trisha', '{sha256}4f7a8586bd3ad985420caeaf20c6c557322d517117efeff63528afbfb277243b5dec5997fba43bee', true);
insert into users (username, password, enabled) values ('james', '{sha256}a9934a3090622d5d72a3e8c5bf6e506684a91923f4609be83a7c1f73c37f8905964aac7bb5174093', true);
insert into users (username, password, enabled) values ('rob', '{bcrypt}$2a$10$P.ZwEJrwC3iCz79IMTL6V.m9AJt93YfqvzJZI24o9S7pYgCiqY0KK', true);
insert into users (username, password, enabled) values ('dashaun', '{bcrypt}$2a$10$emGMOOymUg/x0HAlgF2oA.nys/pSndIk89535xQQM0b/WT3TFl92S', true);
insert into authorities (username, authority) values ('catherine', 'ROLE_USER');
insert into authorities (username, authority) values ('josh', 'ROLE_USER');
insert into authorities (username, authority) values ('mala', 'ROLE_USER');
insert into authorities (username, authority) values ('trisha', 'ROLE_USER');
insert into authorities (username, authority) values ('james', 'ROLE_USER');
insert into authorities (username, authority) values ('rob', 'ROLE_USER');
insert into authorities (username, authority) values ('dashaun', 'ROLE_USER');


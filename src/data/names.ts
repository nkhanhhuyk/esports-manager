/**
 * Culturally-appropriate name pools (fictional combinations of common names) and gamer-tag
 * vocabulary. Space separated for compactness. `eastern` cultures display family name first.
 */

export interface CulturePool {
  first: string[];
  last: string[];
  eastern?: boolean;
  handleRoots?: string[];
}

const p = (s: string): string[] => s.trim().split(/\s+/);

export const CULTURES: Record<string, CulturePool> = {
  english: {
    first: p('James Liam Noah Ethan Mason Logan Lucas Jack Owen Caleb Ryan Tyler Dylan Jordan Austin Connor Kyle Brandon Emma Olivia Chloe Hannah Grace Madison Ava Zoe Riley Cameron'),
    last: p('Smith Johnson Williams Brown Jones Miller Davis Wilson Anderson Taylor Thomas Moore Martin Jackson Thompson White Harris Clark Lewis Walker Hall Young Allen Wright Scott Green Baker Carter Mitchell Turner'),
  },
  spanish: {
    first: p('Santiago Mateo Sebastián Diego Alejandro Nicolás Daniel Gabriel Andrés Javier Carlos Luis Miguel Pablo Adrián Hugo Álvaro Emilio Tomás Joaquín Valentina Camila Sofía Lucía Martina Daniela Paula Ximena'),
    last: p('García Rodríguez Martínez López González Hernández Pérez Sánchez Ramírez Torres Flores Rivera Gómez Díaz Morales Castro Ortiz Vargas Romero Herrera Medina Aguilar Ruiz Navarro Silva Rojas Mendoza Cruz'),
  },
  portuguese: {
    first: p('João Pedro Lucas Gabriel Matheus Rafael Gustavo Felipe Bruno Thiago Vinícius Leonardo Caio Henrique Rodrigo Diogo Tiago Arthur Davi Enzo Ana Beatriz Larissa Mariana Juliana Camila Fernanda'),
    last: p('Silva Santos Oliveira Souza Lima Pereira Costa Ferreira Rodrigues Almeida Carvalho Gomes Martins Araújo Ribeiro Barbosa Rocha Dias Monteiro Cardoso Teixeira Moreira Nunes Mendes Pinto Correia'),
  },
  french: {
    first: p('Lucas Hugo Louis Gabriel Arthur Jules Nathan Théo Raphaël Maxime Antoine Baptiste Clément Mathis Quentin Romain Alexandre Thomas Léa Chloé Manon Camille Inès Jade Sarah'),
    last: p('Martin Bernard Dubois Thomas Robert Richard Petit Durand Leroy Moreau Simon Laurent Lefebvre Michel Garcia David Bertrand Roux Vincent Fournier Morel Girard Andre Mercier Dupont Lambert Bonnet'),
  },
  german: {
    first: p('Lukas Leon Finn Jonas Paul Elias Felix Maximilian Noah Luis Tim Jan Niklas Tobias Moritz Julian Fabian Florian Sebastian Kevin Mia Lena Lea Anna Laura Sophie'),
    last: p('Müller Schmidt Schneider Fischer Weber Meyer Wagner Becker Schulz Hoffmann Koch Bauer Richter Klein Wolf Schröder Neumann Schwarz Zimmermann Braun Krüger Hofmann Hartmann Lange Werner Krause'),
  },
  italian: {
    first: p('Francesco Alessandro Lorenzo Matteo Leonardo Andrea Gabriele Riccardo Tommaso Davide Federico Luca Marco Simone Giuseppe Antonio Nicolò Edoardo Giulia Sofia Aurora Alice Chiara'),
    last: p('Rossi Russo Ferrari Esposito Bianchi Romano Colombo Ricci Marino Greco Bruno Gallo Conti De Luca Costa Giordano Mancini Rizzo Lombardi Moretti Barbieri Fontana Santoro Mariani'),
  },
  dutch: {
    first: p('Daan Sem Lucas Levi Finn Milan Jesse Bram Thijs Lars Ruben Stijn Niels Joris Wouter Jeroen Pieter Sanne Lotte Fleur Eva Noor'),
    last: p('de Jong Jansen de Vries van den Berg van Dijk Bakker Janssen Visser Smit Meijer de Boer Mulder de Groot Bos Vos Peters Hendriks van Leeuwen Dekker Brouwer Maes Claes Peeters'),
  },
  nordic: {
    first: p('Oscar William Lucas Elias Hugo Oliver Emil Magnus Mikkel Rasmus Jesper Anders Frederik Mathias Viktor Jonas Kasper Aleksi Eetu Juho Ida Freja Maja Emma Linnea'),
    last: p('Hansen Nielsen Jensen Pedersen Andersen Larsen Johansson Karlsson Nilsson Eriksson Lindqvist Berg Olsen Haugen Johansen Virtanen Korhonen Mäkinen Nieminen Lindström Sandberg Holm Dahl Lund'),
  },
  polish: {
    first: p('Jakub Kacper Szymon Filip Mateusz Bartosz Michał Wiktor Piotr Paweł Kamil Tomasz Marcin Krzysztof Dawid Patryk Damian Julia Zuzanna Maja Natalia'),
    last: p('Nowak Kowalski Wiśniewski Wójcik Kowalczyk Kamiński Lewandowski Zieliński Szymański Woźniak Dąbrowski Kozłowski Jankowski Mazur Kwiatkowski Krawczyk Piotrowski Grabowski Pawlak Michalski'),
  },
  czech: {
    first: p('Jakub Jan Tomáš Matěj Adam Ondřej Lukáš Filip Vojtěch David Martin Petr Michal Marek Peter Juraj Tereza Eliška Natália'),
    last: p('Novák Svoboda Novotný Dvořák Černý Procházka Kučera Veselý Horák Němec Marek Pokorný Král Horváth Kováč Varga Tóth Baláž Szabó'),
  },
  hungarian: {
    first: p('Bence Máté Levente Ádám Dávid Dániel Balázs Márk Gergő Tamás Zoltán Péter Norbert Kristóf Hanna Anna Luca Réka'),
    last: p('Nagy Kovács Tóth Szabó Horváth Varga Kiss Molnár Németh Farkas Balogh Papp Takács Juhász Lakatos Mészáros Oláh Simon'),
    eastern: true,
  },
  romanian: {
    first: p('Andrei Alexandru Mihai Ionuț Ștefan Gabriel David Cristian Vlad Răzvan Bogdan Florin Adrian Radu Ioana Maria Elena Andreea'),
    last: p('Popescu Ionescu Popa Dumitru Stan Stoica Gheorghe Matei Ciobanu Rusu Munteanu Constantin Moldovan Lungu Florea Dinu Marin Toma'),
  },
  slavic_south: {
    first: p('Luka Nikola Marko Stefan Aleksandar Filip Petar Ivan Dimitar Georgi Nikolay Miloš Nemanja Vuk Matej Jan Ana Milica Elena Maja'),
    last: p('Jovanović Petrović Nikolić Marković Đorđević Stojanović Ilić Kovačević Horvat Novak Babić Marić Ivanov Georgiev Dimitrov Petrov Hadžić Mehmedović Kranjc Zupan'),
  },
  greek: {
    first: p('Giorgos Dimitris Nikos Kostas Giannis Christos Panagiotis Vasilis Alexandros Thanasis Stelios Michalis Maria Eleni Katerina Sofia'),
    last: p('Papadopoulos Papadakis Georgiou Nikolaidis Karagiannis Vlachos Dimitriou Pappas Oikonomou Konstantinidis Makris Antoniou Ioannou Christodoulou'),
  },
  baltic: {
    first: p('Lukas Matas Jonas Dominykas Mantas Martynas Jānis Artūrs Roberts Kristaps Edgars Karl Markus Rasmus Kristjan Laura Elza Liis'),
    last: p('Kazlauskas Jankauskas Petrauskas Stankevičius Vasiliauskas Bērziņš Kalniņš Ozoliņš Liepiņš Krūmiņš Tamm Saar Sepp Mägi Kask Kukk'),
  },
  russian: {
    first: p('Aleksandr Dmitry Maksim Ivan Artyom Nikita Mikhail Daniil Egor Kirill Andrey Ilya Roman Sergey Vladislav Denys Oleksandr Bohdan Anastasia Daria Polina'),
    last: p('Ivanov Smirnov Kuznetsov Popov Vasiliev Petrov Sokolov Mikhailov Novikov Fedorov Morozov Volkov Lebedev Kozlov Kovalenko Shevchenko Bondarenko Tkachenko Melnyk Kravchenko Volkova'),
  },
  central_asian: {
    first: p('Nursultan Arman Dias Alikhan Timur Ruslan Daniyar Bekzat Azamat Aibek Sanjar Jasur Dilshod Kanat Aigerim Madina'),
    last: p('Nurmagambetov Abdrakhmanov Seitkali Zhumabayev Akhmetov Bekov Karimov Rakhimov Yusupov Tashkentov Umarov Saidov Mamytov Asanov Alimov Ermekov'),
  },
  caucasus: {
    first: p('Giorgi Luka Davit Levan Nika Irakli Aram Hayk Tigran Armen Elvin Ramil Farid Orxan Nino Ani'),
    last: p('Beridze Kapanadze Gelashvili Maisuradze Lomidze Hovhannisyan Harutyunyan Sargsyan Grigoryan Petrosyan Mammadov Aliyev Hasanov Huseynov Guliyev'),
  },
  mongolian: {
    first: p('Bat-Erdene Temuulen Enkhbold Ganbaatar Munkh-Erdene Tsogt Khulan Bilguun Erdene Altangerel Sukhbaatar Batbayar Oyun Anar'),
    last: p('Batbold Gantulga Dorj Bold Tsend Purev Enkhtaivan Ochir Sukh Bayar Jargal Damdin Lkhagva Nyam'),
  },
  turkish: {
    first: p('Yusuf Eymen Mustafa Emir Kerem Ömer Ahmet Mehmet Burak Emre Can Berk Arda Kaan Deniz Efe Mert Zeynep Elif Ece'),
    last: p('Yılmaz Kaya Demir Şahin Çelik Yıldız Yıldırım Öztürk Aydın Özdemir Arslan Doğan Kılıç Aslan Çetin Kara Koç Kurt Özkan Şimşek'),
  },
  arabic: {
    first: p('Mohammed Ahmed Abdullah Omar Ali Yousef Khaled Faisal Hamza Ibrahim Saad Fahad Sultan Rashid Tariq Karim Nasser Ziad Layla Noor Mariam'),
    last: p('Al-Harbi Al-Qahtani Al-Otaibi Al-Ghamdi Al-Shehri Al-Dosari Haddad Mansour Khalil Saleh Nasser Hassan Farouk Mahmoud Abdelrahman El-Sayed Benali Bouzid Amrani Rahmani'),
  },
  persian: {
    first: p('Amir Ali Mohammad Reza Hossein Mehdi Arash Kian Dariush Behnam Sina Parsa Kourosh Navid Sara Shirin'),
    last: p('Hosseini Ahmadi Rezaei Mohammadi Karimi Moradi Jafari Rahimi Hashemi Sadeghi Tehrani Shirazi Ghasemi Kazemi Farahani'),
  },
  african_south: {
    first: p('Thabo Sipho Lwazi Kagiso Bongani Themba Mandla Lethabo Tshepo Ruan Pieter Johan Liam Naledi Zanele'),
    last: p('Nkosi Dlamini Ndlovu Khumalo Mokoena Mthembu Zulu Mahlangu Naidoo van der Merwe Botha Pretorius Smit Molefe Sithole'),
  },
  african_west: {
    first: p('Chinedu Emeka Tunde Oluwaseun Ifeanyi Kwame Kofi Yaw Kwabena Samuel Daniel Femi Babajide Moussa Mamadou Ousmane Amara Ngozi Abena Fatou'),
    last: p('Okafor Adeyemi Okonkwo Balogun Eze Nwosu Mensah Owusu Asante Boateng Osei Adebayo Diallo Ndiaye Sow Fall Diop Abiodun'),
  },
  african_east: {
    first: p('Brian Kevin Dennis Collins Felix Abebe Dawit Yonas Bereket Tesfaye Juma Baraka Otieno Wanjiru Amani Hana'),
    last: p('Kamau Mwangi Ochieng Otieno Kiprop Wanjala Kariuki Njoroge Tadesse Haile Bekele Gebre Alemu Mekonnen Mohamed Odhiambo'),
  },
  korean: {
    first: p('Min-jun Seo-jun Ji-hoon Hyun-woo Do-hyun Sang-hyeok Jae-won Woo-jin Seung-ho Dong-hyun Ji-ho Tae-yang Joon-young Hyeon-seok Sung-min Yong-hyeon Gwang-hee Su-hwan Ye-jin Ji-woo Seo-yeon Ha-eun'),
    last: p('Kim Lee Park Choi Jung Kang Cho Yoon Jang Lim Han Oh Seo Shin Kwon Hwang Ahn Song Yoo Hong Moon Jeon Bae Baek'),
    eastern: true,
  },
  chinese: {
    first: p('Hao Yu Jie Wei Lei Tao Bo Chen Xin Jian Yang Hui Peng Zhi Kai Ming Rui Zihao Yuxuan Haoran Junjie Zhiwei Yiming Xiaolong Jiahao Yifan Mei Ling Yan'),
    last: p('Wang Li Zhang Liu Chen Yang Huang Zhao Wu Zhou Xu Sun Ma Zhu Hu Guo He Lin Gao Luo Zheng Liang Xie Song Tang Han Feng Deng'),
    eastern: true,
  },
  taiwanese: {
    first: p('Yu-Ting Chia-Hao Chun-Wei Po-Han Tsung-Han Wei-Lun Chih-Hao Kuan-Yu Yi-Hsuan Hsin-Yi Chen-Yu Ming-Hsuan Shih-Kai Pei-Chen Hao-Wei Ting-Yu'),
    last: p('Chen Lin Huang Chang Lee Wang Wu Liu Tsai Yang Hsu Cheng Hsieh Kuo Hung Tseng Chiu Lai Chou Chiang'),
    eastern: true,
  },
  cantonese: {
    first: p('Ka-Ho Chun-Kit Wing-Hong Ho-Yin Tsz-Hin Man-Lok Siu-Fai Kin-Wai Pak-Hei Hoi-Ching Wing-Yan Ka-Yan Cheuk-Hin Lok-Yiu'),
    last: p('Chan Wong Cheung Lau Lee Ng Ho Leung Lam Tang Yip Kwok Tse Fung Mak Tsang Choi Ip'),
    eastern: true,
  },
  japanese: {
    first: p('Haruto Yuto Sota Yuki Hayato Haruki Ryusei Koki Sora Riku Takumi Kaito Daiki Ren Shota Kenta Yusuke Hikaru Sakura Yui Hina Aoi'),
    last: p('Sato Suzuki Takahashi Tanaka Watanabe Ito Yamamoto Nakamura Kobayashi Kato Yoshida Yamada Sasaki Yamaguchi Matsumoto Inoue Kimura Hayashi Shimizu Mori Ikeda Hashimoto'),
    eastern: true,
  },
  vietnamese: {
    first: p('Văn An Minh Tuấn Quang Huy Đức Anh Hoàng Nam Thành Long Gia Bảo Minh Khoa Hữu Phát Tiến Dũng Quốc Bảo Trọng Hiếu Đình Phúc Thị Lan Ngọc Hân'),
    last: p('Nguyễn Trần Lê Phạm Hoàng Huỳnh Phan Vũ Võ Đặng Bùi Đỗ Hồ Ngô Dương Lý'),
    eastern: true,
  },
  thai: {
    first: p('Somchai Anan Kittisak Nattapong Phakphum Thanakorn Chaiwat Pongsakorn Sarawut Wichai Kritsada Thanawat Jirayu Teerapat Siriporn Pimchanok Kanokwan'),
    last: p('Saetang Srisuk Wongsawat Rattanakorn Chaiyaporn Boonmee Kaewkla Somboon Phrommin Thongdee Charoensuk Suwannarat Panyasiri Inthawong Kongsawat'),
  },
  indonesian: {
    first: p('Budi Agus Rizky Dimas Fajar Arif Bayu Eko Hendra Yoga Aditya Reza Teguh Ilham Rafi Galih Wahyu Putra Bagas Kevin Siti Putri Dewi'),
    last: p('Santoso Wijaya Saputra Pratama Hidayat Kurniawan Setiawan Nugroho Susanto Gunawan Purnomo Firmansyah Ramadhan Hakim Wibowo Siregar Simanjuntak Halim Lubis Sitompul'),
  },
  filipino: {
    first: p('Juan Jose Mark John Paul Christian Angelo Carlo Miguel Rafael Joshua Kenneth Jerome Karl Vincent Renz Jayvee Kyle Andrea Nicole Patricia Bea'),
    last: p('Santos Reyes Cruz Bautista Ocampo Garcia Mendoza Torres Tomas Andrada Castillo Villanueva Ramos Aquino Navarro Dela Cruz Salazar Gonzales Flores Manalo Pascual Dizon'),
  },
  malay: {
    first: p('Muhammad Ahmad Amirul Haziq Danish Faris Iman Aiman Syafiq Hakim Izzat Nabil Firdaus Zulkifli Adam Nur Aisyah Siti'),
    last: p('Abdullah Rahman Ismail Ibrahim Hassan Yusof Osman Razak Hamid Aziz Kamal Salleh Mahmud Zainal Ahmad Hussein'),
  },
  khmer: {
    first: p('Sokha Dara Vannak Rithy Sophal Chanthy Piseth Visal Sokun Bunthoeun Kosal Ratanak Sreymom Chenda'),
    last: p('Sok Chan Kim Heng Ly Meas Chea Keo Pich Phan Ouk Nhem Sem Prak'),
    eastern: true,
  },
  burmese: {
    first: p('Aung Kyaw Zaw Htet Min Thura Ye Naing Myo Win Soe Thant Hlaing Phyo Su Nandar'),
    last: p('Aung Htun Oo Myint Lwin Kyaw Naing Tun Zaw Hein Thein Maung Win Soe'),
  },
  indian: {
    first: p('Aarav Vivaan Aditya Arjun Sai Reyansh Krishna Ishaan Rohan Karan Rahul Aman Nikhil Siddharth Varun Yash Pranav Ananya Diya Priya Kavya'),
    last: p('Sharma Verma Gupta Singh Kumar Patel Reddy Nair Iyer Rao Das Joshi Mehta Chopra Malhotra Bose Mukherjee Kapoor Thakur Pillai Shrestha Perera'),
  },
  pakistani: {
    first: p('Ali Hamza Hassan Usman Bilal Zain Ahmed Faizan Saad Talha Hamid Imran Shahid Kashif Ayesha Fatima'),
    last: p('Khan Ahmed Ali Hussain Malik Qureshi Siddiqui Butt Chaudhry Sheikh Iqbal Raza Shah Akhtar Mirza Javed'),
  },
  bengali: {
    first: p('Rahim Karim Tanvir Arif Sakib Tamim Nayeem Rafiq Shuvo Fahim Imran Sabbir Nusrat Farhana'),
    last: p('Hossain Rahman Islam Ahmed Chowdhury Uddin Alam Haque Miah Sarkar Das Roy Akter Begum'),
  },
};

export const HANDLE_ROOTS = p(`
Ace Apex Arc Ash Astra Atlas Aura Axe Bane Bash Blaze Blitz Bolt Brisk Byte Cairo Cipher Clutch Comet Crow Cyan Dash Dawn Delta Drift
Echo Ember Envy Fable Fang Flux Frost Fury Gale Ghost Glint Grim Halo Havoc Hex Hydra Icarus Ion Jade Jinx Juno Karma Kite Knox Kuro
Lance Lux Lynx Mako Maverick Mint Mirage Mono Moss Myth Neon Nexus Nimbus Nova Nyx Omen Onyx Orbit Pace Phantom Pixel Plume Prism Pulse
Quake Quill Rage Raven Rex Rift Rogue Rune Rush Saber Sage Scout Shade Shift Shiro Silk Slate Sly Snipe Sol Sonic Spark Specter Spike
Storm Sway Talon Tempo Thorn Titan Torch Trace Trick Tron Valk Vane Vex Viper Void Volt Warden Wisp Wolf Wraith Xeno Yeti Zen Zephyr Zero
Akira Bento Chibi Daku Hanzo Kage Kenji Neko Ronin Sakura Taiyo Yami Hwa Jin Ryu Seon Tae Bao Feng Long Mei Shen Xiao Yun Zhan
Diablo Fuego Lobo Rayo Sombra Toro Fenix Brasa Garra Cobra Onca Raio Leao Corvo Chama Nebel Blitzer Falke Sturm Wulf Krieg Loup Ombre
Volk Medved Grom Sokol Veter Zmey Kurt Kartal Simsek Atesh Shahin Nimr Saqr Barq Layth Rudra Agni Vayu Garuda Naga Singha Hanuman Bayu
Elang Macan Petir Kidlat Bagwis Halimaw Tigre Dragon Buwan Garuda Sangkil Phoenix Tempest Rapid Steel Iron Glacier Ignite Prowl Wick
Kairo Zeta Sigma Omega Lambda Theta Kappa Gamma Vega Rigel Altair Sirius Draco Lyra Orion Ares Hermes Loki Odin Freya Thor Tyr Kami
`);

export const HANDLE_PREFIX = p('x i Mr Lil Big Its The Dr Sir Kid Lord Dark Neo Pro Ex Un');
export const HANDLE_SUFFIX = p('X Z GG TV Jr Prime Kun Sama ito ix ox er ie y z zz o');

/** Fictional organisation name vocabulary. */
export const ORG_WORDS_A = p(`
Apex Arcane Astral Azure Black Blazing Blue Bold Celestial Chrome Cobalt Crimson Crystal Cyber Dark Dawn Eclipse Electric Elite Emerald
Eternal Falcon Fearless Fire Frost Ghost Golden Grand Gravity Hyper Imperial Infinite Iron Jade Kinetic Last Liberty Lunar Midnight
Mighty Neon Noble Northern Nova Obsidian Omega Onyx Phantom Pixel Polar Prime Pulse Quantum Radiant Raging Red Rising Royal Ruby
Savage Scarlet Shadow Silent Silver Solar Sonic Southern Spectral Steel Storm Sunset Supreme Swift Thunder Titan True Twin Ultra United
Vanguard Velvet Vertex Vivid Void White Wild Wired Zenith
`);

export const ORG_WORDS_B = p(`
Alliance Angels Arrows Aurora Bears Bison Blades Bolts Brigade Bulls Coalition Collective Comets Corsairs Crows Crusaders
Dragons Dynasty Eagles Empire Esports Falcons Foxes Gaming Giants Gladiators Griffins Guardians Hawks Heroes Hornets Horizon Hounds
Hunters Hydra Jaguars Kings Knights Legends Legion Leopards Lions Lynx Mantis Monarchs Mustangs Ninjas Nomads Owls Panthers Phoenix
Pirates Pumas Raiders Rams Ravens Rebels Rhinos Rockets Rogues Sentinels Serpents Sharks Spartans Squad Stallions Storm Syndicate Tigers
Titans Tribe Union Unit Valkyries Vipers Vortex Warriors Wolves Wyverns Zenith
`);

export const ORG_SUFFIX = p('Esports Gaming Club Collective Esports Gaming');

export const COMPANY_WORDS = p('Nexa Volta Orion Helix Kairo Vanta Lumen Strata Quanta Zentra Arcus Polaris Meridian Atlas Cobalt Vertex Solace Brightline Northwind Sunrise Pinnacle Harbor Summit Evergreen Ironclad Bluewave');
export const COMPANY_TYPES = p('Energy Telecom Bank Motors Mobile Cloud Foods Drinks Apparel Hardware Peripherals Airlines Insurance Labs Logistics Games Media Pay');
